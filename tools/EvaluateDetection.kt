package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingResult
import com.example.lightrhythm.model.BandingStatus
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/** Desktop-only evaluation; labels describe visible luminance bands, not their physical cause. */
internal data class EvaluationSample(
    val id: String, val image: File, val label: String, val split: String, val group: String,
)

internal data class EvaluationRecord(
    val sample: EvaluationSample, val result: BandingResult? = null,
    val elapsedMs: Double? = null, val error: String = "",
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
}

internal fun readEvaluationManifest(manifest: File): List<EvaluationSample> {
    val lines = manifest.readLines(Charsets.UTF_8)
    require(lines.isNotEmpty() && lines.first().removePrefix("\uFEFF") == "id\tpath\tlabel\tsplit\tgroup") {
        "TSV header must be: id, path, label, split, group (tab-separated)"
    }
    val samples = lines.drop(1).filter { it.isNotBlank() }.mapIndexed { index, line ->
        val columns = line.split('\t')
        require(columns.size == 5 && columns.all { it.isNotBlank() }) { "Invalid manifest row ${index + 2}" }
        val (id, path, label, split, group) = columns
        require(label in setOf("banding", "clean", "unknown")) { "Invalid label for $id: $label" }
        require(split in setOf("dev", "validation")) { "Invalid split for $id: $split" }
        val file = File(path).let { if (it.isAbsolute) it else File(manifest.absoluteFile.parentFile, path) }.canonicalFile
        EvaluationSample(id, file, label, split, group)
    }
    require(samples.isNotEmpty()) { "Manifest has no samples" }
    require(samples.map { it.id }.distinct().size == samples.size) { "Duplicate sample IDs" }
    require(samples.map { it.image.path.lowercase(Locale.ROOT) }.distinct().size == samples.size) { "Duplicate image paths" }
    require(samples.groupBy { it.group }.values.all { group -> group.map { it.split }.distinct().size == 1 }) {
        "A scene/source group cannot appear in both dev and validation"
    }
    return samples
}

internal fun evaluateSample(sample: EvaluationSample): EvaluationRecord = try {
    val image = ImageIO.read(sample.image) ?: error("Unsupported or damaged image")
    try {
        val start = System.nanoTime()
        val profiles = readDetectionProfiles(image.width, image.height) { y, pixels ->
            image.getRGB(0, y, image.width, 1, pixels, 0, image.width)
        }
        val result = analyzeDetectionProfiles(profiles)
        EvaluationRecord(sample, result, (System.nanoTime() - start) / 1_000_000.0)
    } finally {
        image.flush()
    }
} catch (failure: Exception) {
    EvaluationRecord(sample, error = failure.message ?: failure.javaClass.simpleName)
}

internal fun metricRows(records: List<EvaluationRecord>): List<List<String>> =
    listOf("dev", "validation").map { split ->
        val subset = records.filter { it.sample.split == split }
        fun count(outcome: String) = subset.count { it.outcome == outcome }
        val tp = count("TP"); val fp = count("FP"); val tn = count("TN"); val fn = count("FN")
        val evaluated = tp + fp + tn + fn
        val labeled = subset.count { it.sample.label != "unknown" }
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
    require(args.size == 2) { "Usage: EvaluateDetectionKt manifest.tsv NEW-report-directory" }
    val samples = readEvaluationManifest(File(args[0]))
    val output = File(args[1]).absoluteFile
    require(!output.exists()) { "Report directory already exists; choose a new path" }
    check(output.mkdirs()) { "Cannot create report directory" }
    val records = samples.map(::evaluateSample)
    fun Double?.text() = this?.let { String.format(Locale.ROOT, "%.6f", it) } ?: ""
    writeCsv(File(output, "results.csv"), listOf(listOf(
        "id", "path", "label", "split", "group", "outcome", "status", "width", "height", "score",
        "periodRows", "rms", "autocorrelation", "regionSupport", "desktopAnalysisMs", "algorithmVersion", "error",
        "luminanceStatus", "colorChannel", "colorPeriodRows", "colorScore", "colorAutocorrelation", "colorRegionSupport",
        "colorTrendRadiusRows", "colorMaxLagRows",
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
            r?.colorEvidence?.effectiveMaxLagRows?.toString() ?: "")
    })
    writeCsv(File(output, "summary.csv"), listOf(listOf(
        "split", "total", "labeled", "evaluated", "errors", "skipped", "unlabeled",
        "TP", "FP", "TN", "FN", "precision", "recall", "falsePositiveRate", "labeledCoverage",
    )) + metricRows(records))
    println("Desktop evaluation: ${records.size} samples; reports: ${output.path}")
    println("Errors=${records.count { it.outcome == "ERROR" }}, skipped=${records.count { it.outcome == "SKIPPED" }}")
}
