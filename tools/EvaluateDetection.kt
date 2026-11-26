package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingResult
import com.example.lightrhythm.model.BandingStatus
import java.io.File
import java.util.Locale
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Desktop-only evaluation; labels describe visible luminance bands, not their physical cause. */
internal data class EvaluationSample(
    val id: String, val image: File, val label: String, val split: String, val group: String,
    val moireLabel: String = "unknown", val contentSha256: String = "",
)

internal data class EvaluationRecord(
    val sample: EvaluationSample, val result: BandingResult? = null,
    val elapsedMs: Double? = null, val error: String = "",
    val moire: MoireResult? = null, val moireElapsedMs: Double? = null,
) {
    val outcome: String get() = when {
        error.isNotEmpty() -> "ERROR"
        result == null || result.status == BandingStatus.INSUFFICIENT_DATA -> "SKIPPED"
        sample.label == "unknown" -> "UNLABELED"
        sample.label == "banding" && result.isSuspected -> "TP"
        sample.label == "banding" -> "FN"
        result.isSuspected -> "FP"
        else -> "TN"
    }
    val moireOutcome: String get() = when {
        error.isNotEmpty() -> "ERROR"
        moire == null || moire.testedTiles == 0 -> "SKIPPED"
        sample.moireLabel == "unknown" -> "UNLABELED"
        sample.moireLabel == "moire" && moire.isSuspected -> "TP"
        sample.moireLabel == "moire" -> "FN"
        moire.isSuspected -> "FP"
        else -> "TN"
    }
}

private fun sha256(file: File): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer=ByteArray(65536)
        while(true) {
            val count=input.read(buffer)
            if(count<0) break
            digest.update(buffer,0,count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun readEvaluationManifest(manifest: File): List<EvaluationSample> {
    val lines = manifest.readLines(Charsets.UTF_8)
    val header=lines.firstOrNull()?.removePrefix("\uFEFF")
    val extended=header=="id\tpath\tlabel\tsplit\tgroup\tmoireLabel"
    require(extended || header=="id\tpath\tlabel\tsplit\tgroup") {
        "TSV header must be: id, path, label, split, group, optional moireLabel (tab-separated)"
    }
    val samples = lines.drop(1).filter { it.isNotBlank() }.mapIndexed { index, line ->
        val columns = line.split('\t')
        require(columns.size == (if(extended) 6 else 5) && columns.all { it.isNotBlank() }) { "Invalid manifest row ${index + 2}" }
        val (id, path, label, split, group) = columns
        require(label in setOf("banding", "clean", "unknown")) { "Invalid label for $id: $label" }
        require(split in setOf("dev", "validation")) { "Invalid split for $id: $split" }
        val file = File(path).let { if (it.isAbsolute) it else File(manifest.absoluteFile.parentFile, path) }.canonicalFile
        val moireLabel=if(extended) columns[5] else "unknown"
        require(moireLabel in setOf("moire","clean","unknown")) { "Invalid moire label for $id: $moireLabel" }
        EvaluationSample(id, file, label, split, group,moireLabel,if(file.isFile) sha256(file) else "")
    }
    require(samples.isNotEmpty()) { "Manifest has no samples" }
    require(samples.map { it.id }.distinct().size == samples.size) { "Duplicate sample IDs" }
    require(samples.map { it.image.path.lowercase(Locale.ROOT) }.distinct().size == samples.size) { "Duplicate image paths" }
    require(samples.groupBy { it.group }.values.all { group -> group.map { it.split }.distinct().size == 1 }) {
        "A scene/source group cannot appear in both dev and validation"
    }
    require(samples.filter { it.contentSha256.isNotEmpty() }.groupBy { it.contentSha256 }.values.all { it.size==1 }) {
        "Duplicate file contents: copied/renamed photos cannot count as independent samples"
    }
    return samples
}

internal fun evaluateSample(sample: EvaluationSample): EvaluationRecord = evaluateSampleAtSize(sample,0)

internal fun evaluateSampleAtSize(sample: EvaluationSample,maxEdge: Int): EvaluationRecord = try {
    val original = ImageIO.read(sample.image) ?: error("Unsupported or damaged image")
    var image=original
    try {
        if(maxEdge>0 && maxOf(original.width,original.height)>maxEdge) {
            val scale=maxEdge.toDouble()/maxOf(original.width,original.height)
            image=java.awt.image.BufferedImage(maxOf(1,(original.width*scale).toInt()),maxOf(1,(original.height*scale).toInt()),
                if(original.colorModel.hasAlpha()) java.awt.image.BufferedImage.TYPE_INT_ARGB else java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original,0,0,image.width,image.height,null); dispose()
            }
        }
        val start = System.nanoTime()
        val reader: PixelTileReader={ x,y,w,h,pixels -> image.getRGB(x,y,w,h,pixels,0,w) }
        val result = analyzeImageBanding(image.width, image.height,readTile=reader) { y, pixels ->
            image.getRGB(0, y, image.width, 1, pixels, 0, image.width)
        }
        val bandingMs=(System.nanoTime()-start)/1_000_000.0
        val moireStart=System.nanoTime()
        val moire=analyzeMoireTiles(image.width,image.height,reader)
        EvaluationRecord(sample,result,bandingMs,moire=moire,moireElapsedMs=(System.nanoTime()-moireStart)/1_000_000.0)
    } finally {
        if(image!==original) image.flush()
        original.flush()
    }
} catch (failure: Exception) {
    EvaluationRecord(sample, error = failure.message ?: failure.javaClass.simpleName)
}

internal fun metricRows(records: List<EvaluationRecord>, forMoire: Boolean=false): List<List<String>> =
    listOf("dev", "validation").map { split ->
        val subset = records.filter { it.sample.split == split }
        fun count(outcome: String) = subset.count { (if(forMoire) it.moireOutcome else it.outcome) == outcome }
        val tp = count("TP"); val fp = count("FP"); val tn = count("TN"); val fn = count("FN")
        val evaluated = tp + fp + tn + fn
        val labeled = subset.count { (if(forMoire) it.sample.moireLabel else it.sample.label) != "unknown" }
        fun ratio(numerator: Int, denominator: Int) =
            if (denominator == 0) "" else String.format(Locale.ROOT, "%.6f", numerator.toDouble() / denominator)
        listOf(split, subset.size.toString(), labeled.toString(), evaluated.toString(),
            count("ERROR").toString(), count("SKIPPED").toString(), count("UNLABELED").toString(),
            tp.toString(), fp.toString(), tn.toString(), fn.toString(),
            ratio(tp, tp + fp), ratio(tp, tp + fn), ratio(fp, fp + tn), ratio(evaluated, labeled))
    }

private fun writeCsv(file: File, rows: List<List<String>>) {
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        // UTF-8 BOM allows Excel on Windows to read Chinese filenames correctly.
        writer.write("\uFEFF")
        rows.forEach { row ->
            writer.write(row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
            writer.newLine()
        }
    }
}

fun main(args: Array<String>) = runEvaluation(args)

internal fun runEvaluation(args: Array<String>) {
    require(args.size in 2..3) { "Usage: EvaluateDetectionKt manifest.tsv NEW-report-directory [max-edge-pixels; 0=native]" }
    val maxEdge=if(args.size==3) args[2].toInt() else 0
    require(maxEdge==0 || maxEdge in 64..8192) { "max-edge must be 0 or 64..8192" }
    val samples = readEvaluationManifest(File(args[0]))
    val output = File(args[1]).absoluteFile
    require(!output.exists()) { "Report directory already exists; choose a new path" }
    check(output.mkdirs()) { "Cannot create report directory" }
    val records = samples.map { evaluateSampleAtSize(it,maxEdge) }
    fun Double?.text() = this?.let { String.format(Locale.ROOT, "%.6f", it) } ?: ""
    writeCsv(File(output, "results.csv"), listOf(listOf(
        "id", "path", "label", "split", "group", "outcome", "status", "width", "height", "score",
        "periodRows", "rms", "autocorrelation", "regionSupport", "desktopAnalysisMs", "algorithmVersion", "error",
        "luminanceStatus", "colorChannel", "colorPeriodRows", "colorScore", "colorAutocorrelation", "colorRegionSupport",
        "colorTrendRadiusRows", "colorMaxLagRows",
        "moireLabel", "moireOutcome", "moireSuspected", "moireCells", "moireAnalysisMs", "moireAlgorithmVersion", "contentSha256",
    )) + records.map { record ->
        val r = record.result; val s = record.sample
        val color = r?.colorEvidence?.channels?.filter { it.status == BandingStatus.SUSPECTED }?.maxByOrNull { it.score }
        listOf(s.id, s.image.path, s.label, s.split, s.group, record.outcome, r?.status?.name ?: "",
            r?.imageWidth?.toString() ?: "", r?.imageHeight?.toString() ?: "", r?.score.text(),
            r?.candidatePeriodRows?.toString() ?: "", r?.rmsAmplitude.text(), r?.autocorrelation.text(),
            r?.regionSupport.text(), record.elapsedMs.text(), r?.algorithmVersion ?: "", record.error,
            r?.colorEvidence?.luminanceStatus?.name ?: r?.status?.name ?: "", color?.channel ?: "",
            color?.candidatePeriodRows?.toString() ?: "", color?.score.text(),
            color?.autocorrelation.text(), color?.regionSupport.text(),
            r?.colorEvidence?.effectiveTrendRadiusRows?.toString() ?: "",
            r?.colorEvidence?.effectiveMaxLagRows?.toString() ?: "",
            s.moireLabel,record.moireOutcome,record.moire?.isSuspected?.toString() ?: "",
            record.moire?.tiles?.size?.toString() ?: "",record.moireElapsedMs.text(),record.moire?.algorithmVersion ?: "",s.contentSha256)
    })
    val summaryHeader=listOf(
        "split", "total", "labeled", "evaluated", "errors", "skipped", "unlabeled",
        "TP", "FP", "TN", "FN", "precision", "recall", "falsePositiveRate", "labeledCoverage",
    )
    writeCsv(File(output,"summary.csv"),listOf(summaryHeader)+metricRows(records))
    writeCsv(File(output,"moire-summary.csv"),listOf(summaryHeader)+metricRows(records,forMoire=true))
    val groupRows=records.groupBy { it.sample.split to it.sample.group }.flatMap { (key,subset) ->
        listOf(false,true).map { forMoire ->
            val row=metricRows(subset,forMoire).first { it[0]==key.first }
            listOf(row[0],key.second,if(forMoire) "moire" else "banding")+row.drop(1)
        }
    }
    writeCsv(File(output,"group-summary.csv"),listOf(listOf("split","group","target")+summaryHeader.drop(1))+groupRows)
    File(output,"protocol.txt").writeText(buildString {
        appendLine("manifestSHA256=${sha256(File(args[0]))}")
        appendLine("maxEdgePixels=$maxEdge (0=native); scaling variants are not independent samples")
        appendLine("Scene groups must be assigned by capture/source, including crops and resized copies.")
        appendLine("Exact duplicate files are rejected; other same-source images require manual grouping.")
        appendLine("Validation independence cannot be established by this tool or a split label alone.")
        appendLine("Any inspected/tuned validation group must become development data for subsequent changes.")
        for(split in listOf("dev","validation")) {
            val subset=samples.filter { it.split==split }
            appendLine("$split: samples=${subset.size}, groups=${subset.map { it.group }.distinct().size}")
        }
    })
    println("Desktop evaluation: ${records.size} samples; reports: ${output.path}")
    println("Errors=${records.count { it.outcome == "ERROR" }}, skipped=${records.count { it.outcome == "SKIPPED" }}")
}
