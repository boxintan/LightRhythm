package com.example.lightrhythm.detection

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.sin

class EvaluationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun image(name: String, width: Int = 64, height: Int = 256, bands: Boolean = false): File {
        val file = File(temporary.root, name)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            val gray = if (bands) (128 + 24 * sin(2 * PI * y / 16)).toInt() else 128
            for (x in 0 until width) image.setRGB(x, y, (gray shl 16) or (gray shl 8) or gray)
        }
        ImageIO.write(image, "png", file)
        return file
    }

    private fun manifest(rows: String): File = File(temporary.root, "samples.tsv").apply {
        writeText("\uFEFFid\tpath\tlabel\tsplit\tgroup\n$rows", Charsets.UTF_8)
    }

    @Test fun evaluatesPixelFixturesAndKeepsFailuresOutsideConfusionMatrix() {
        val bands = image("bands.png", bands = true)
        val clean = image("clean.png")
        val small = image("small.png", height = 20)
        val samples = listOf(
            EvaluationSample("tp", bands, "banding", "dev", "a"),
            EvaluationSample("fp", bands, "clean", "dev", "b"),
            EvaluationSample("tn", clean, "clean", "dev", "c"),
            EvaluationSample("fn", clean, "banding", "dev", "d"),
            EvaluationSample("unknown", bands, "unknown", "dev", "e"),
            EvaluationSample("small", small, "banding", "dev", "f"),
            EvaluationSample("missing", File(temporary.root, "missing.png"), "clean", "dev", "g"),
        )
        val records = samples.map(::evaluateSample)
        assertEquals(listOf("TP", "FP", "TN", "FN", "UNLABELED", "SKIPPED", "ERROR"), records.map { it.outcome })
        val summary = metricRows(records)
        assertEquals(listOf("dev", "7", "6", "4", "1", "1", "1", "1", "1", "1", "1",
            "0.500000", "0.500000", "0.500000", "0.666667"), summary.first())
        assertEquals(listOf("", "", "", ""), summary.last().takeLast(4))
    }

    @Test fun resolvesRelativePathsAndAcceptsBomAndSpaces() {
        val file = image("photo space.png")
        val samples = readEvaluationManifest(manifest("one\tphoto space.png\tunknown\tdev\tscene-1\n"))
        assertEquals(file.canonicalFile, samples.single().image)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSceneLeakageBetweenDevelopmentAndValidation() {
        readEvaluationManifest(manifest("a\ta.png\tclean\tdev\tscene\nb\tb.png\tbanding\tvalidation\tscene\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePathsEvenWithDifferentSpelling() {
        readEvaluationManifest(manifest("a\ta.png\tclean\tdev\ta\nb\t./a.png\tclean\tdev\tb\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLabelsInsteadOfCountingThemAsClean() {
        readEvaluationManifest(manifest("a\ta.png\tmaybe\tdev\ta\n"))
    }

    @Test fun writesBothReportsAndRefusesToOverwritePreviousRun() {
        image("band,1.png", bands = true)
        val input = manifest("one\"quoted\tband,1.png\tbanding\tdev\tscene-1\n")
        val output = File(temporary.root, "report")
        runEvaluation(arrayOf(input.path, output.path))
        val result = File(output, "results.csv").readText()
        assertTrue(result.contains("\"one\"\"quoted\""))
        assertTrue(result.contains("band,1.png"))
        assertTrue(result.contains("\"TP\""))
        assertTrue(File(output, "summary.csv").isFile)
        assertTrue(File(output, "moire-summary.csv").isFile)
        val groupReport=File(output,"group-summary.csv").readText()
        assertTrue(groupReport.contains("\"scene-1\",\"banding\""))
        assertTrue(groupReport.contains("\"scene-1\",\"moire\""))
        assertTrue(File(output, "protocol.txt").readText().contains("manifestSHA256="))
        val original = File(output, "results.csv").readBytes()
        try {
            runEvaluation(arrayOf(input.path, output.path))
            fail("Existing report should be preserved")
        } catch (expected: IllegalArgumentException) {
            assertArrayEquals(original, File(output, "results.csv").readBytes())
        }
    }

    @Test fun copiedFilesCannotLeakAcrossGroupsOrSplits() {
        val original=image("original.png")
        original.copyTo(File(temporary.root,"renamed.png"))
        try {
            readEvaluationManifest(manifest("a\toriginal.png\tclean\tdev\ta\nb\trenamed.png\tclean\tvalidation\tb\n"))
            fail("Renaming identical content must not create an independent validation sample")
        } catch(expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate file contents"))
        }
    }

    @Test fun moireLabelsAreIndependentAndMissingLabelsNeverMeanClean() {
        val file=image("full.png",256,256)
        val header="id\tpath\tlabel\tsplit\tgroup\tmoireLabel\n"
        val input=File(temporary.root,"extended.tsv").apply {
            writeText(header+"one\tfull.png\tunknown\tdev\tscene\tmoire\n")
        }
        val sample=readEvaluationManifest(input).single()
        assertEquals("moire",sample.moireLabel)
        assertEquals(64,sample.contentSha256.length)
        val record=evaluateSample(sample)
        assertEquals("UNLABELED",record.outcome)
        assertEquals("FN",record.moireOutcome)
        assertEquals("UNLABELED",evaluateSample(sample.copy(moireLabel="unknown")).moireOutcome)
        assertEquals("TN",evaluateSample(sample.copy(moireLabel="clean")).moireOutcome)
        assertEquals("unknown",readEvaluationManifest(manifest("one\tfull.png\tclean\tdev\tscene\n")).single().moireLabel)
        assertTrue(file.isFile)
    }

    @Test fun moireMetricsDoNotCountUnknownTargetsOrMissingPixelsAsNegatives() {
        val sample=EvaluationSample("x",File("unused"),"unknown","validation","scene",moireLabel="moire")
        val records=listOf(
            EvaluationRecord(sample,moire=MoireResult(emptyList(),16)),
            EvaluationRecord(sample.copy(moireLabel="clean"),moire=MoireResult(emptyList(),16)),
            EvaluationRecord(sample.copy(moireLabel="unknown"),moire=MoireResult(emptyList(),16)),
            EvaluationRecord(sample,moire=MoireResult(emptyList(),0)),
        )
        assertEquals(listOf("FN","TN","UNLABELED","SKIPPED"),records.map { it.moireOutcome })
        val row=metricRows(records,true).last()
        assertEquals(listOf("0","1","1","0","0","1","1"),row.subList(4,11))
        assertEquals("0.666667",row.last())
    }

    @Test fun explicitPreviewConditionIsRecordedAndDoesNotChangeSourceFiles() {
        val file=image("preview.png",256,512)
        val before=file.readBytes()
        val input=manifest("one\tpreview.png\tclean\tdev\tsource-scene\n")
        val output=File(temporary.root,"preview-report")
        runEvaluation(arrayOf(input.path,output.path,"256"))
        val report=File(output,"results.csv").readText()
        assertTrue(report.contains("\"128\",\"256\""))
        assertTrue(File(output,"protocol.txt").readText().contains("maxEdgePixels=256"))
        assertArrayEquals(before,file.readBytes())
    }
}
