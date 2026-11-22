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
        val original = File(output, "results.csv").readBytes()
        try {
            runEvaluation(arrayOf(input.path, output.path))
            fail("Existing report should be preserved")
        } catch (expected: IllegalArgumentException) {
            assertArrayEquals(original, File(output, "results.csv").readBytes())
        }
    }
}
