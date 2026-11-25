package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.LocalBandingEvidence
import com.example.lightrhythm.model.LocalStripeTile
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fallback for local/slightly tilted bands that cancel in a whole-image row average. */
internal fun analyzeLocalBanding(width: Int, height: Int, config: DetectionConfig = DetectionConfig(),
    readRow: (Int, IntArray) -> Unit): LocalBandingEvidence? {
    val size = 128
    val margin = 16
    if (width < 256 || height < 256) return null
    val pixels = IntArray(width)
    val candidates = mutableListOf<LocalStripeTile>()
    for (gy in 0..3) for (gx in 0..5) {
        val left = ((width - size) * (gx + 0.5) / 6).toInt()
        val top = margin + ((height - size - 2*margin) * (gy + 0.5) / 4).toInt()
        val values = DoubleArray(size * (size+2*margin))
        for (y in 0 until size+2*margin) {
            readRow(top-margin+y, pixels)
            for (x in 0 until size) {
                val pixel = pixels[left+x]
                values[y*size+x] = if (pixel ushr 24 == 255) pixelLuminance(pixel) else Double.NaN
            }
        }
        var best: LocalStripeTile? = null
        for (slopeIndex in -4..4) {
            val slope = slopeIndex * 0.05
            val profiles = List(4) { DoubleArray(size) }
            var usable = true
            for (y in 0 until size) for (region in 0..3) {
                val samples = DoubleArray(32) { offset ->
                    val x = region*32+offset
                    val yy = y+margin+(slope*(x-63.5)).roundToInt()
                    values[yy*size+x]
                }
                if (samples.any { !it.isFinite() }) { usable = false; break }
                samples.sort()
                // Upper quartile suppresses sparse dark glyphs while retaining broad dark bands.
                profiles[region][y] = samples[24]
            }
            if (!usable) continue
            // Suppress pixel grain in the narrow-column quantiles before testing recurrence.
            val filtered = profiles.map { profile -> DoubleArray(size) { y ->
                (profile[maxOf(0,y-1)] + 2*profile[y] + profile[minOf(size-1,y+1)]) / 4
            } }
            val result = analyzeBandingProfiles(filtered, List(4) { 32 }, config)
            val period = result.candidatePeriodRows ?: continue
            // Local projection perspective can retain a small phase shift between columns.
            // Require the same spacing, amplitude and half-cycle reversal, not whole-frame phase.
            val periodicRegions = filtered.count { profile ->
                val trend = localLinearTrend(profile, minOf(config.maxPeriodRows, size/config.minCycles))
                val residual = DoubleArray(size) { profile[it]-trend[it] }
                kotlin.math.sqrt(residual.sumOf { it*it }/size) >= config.minRmsAmplitude &&
                    correlation(residual,residual,period) >= config.minAutocorrelation &&
                    correlation(residual,residual,period/2) <= -0.15
            }
            if (periodicRegions / 4.0 < config.minRegionSupport) continue
            if (best == null || result.autocorrelation > best.correlation) {
                best = LocalStripeTile(left, top, size, period,
                    slope, result.autocorrelation, gx, gy)
            }
        }
        best?.let { candidates += it }
    }
    // A repeated structure must agree in spacing and slope across several spatial positions.
    val cluster = candidates.map { anchor -> candidates.filter {
        abs(it.periodRows-anchor.periodRows) <= maxOf(1.0, anchor.periodRows*0.2) &&
            abs(it.slope-anchor.slope) <= 0.1+1e-9
    } }.filter { it.size >= 4 && it.map { tile -> tile.column }.distinct().size >= 2 &&
        it.map { tile -> tile.row }.distinct().size >= 2 }.maxByOrNull { it.size }
    return cluster?.let { LocalBandingEvidence(it) }
}
