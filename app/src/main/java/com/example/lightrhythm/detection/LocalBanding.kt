package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.LocalBandingEvidence
import com.example.lightrhythm.model.LocalStripeTile
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fallback for local/slightly tilted bands that cancel in a whole-image row average. */
internal fun analyzeLocalBanding(width: Int, height: Int, config: DetectionConfig = DetectionConfig(),
    readRow: (Int, IntArray) -> Unit): LocalBandingEvidence? =
    analyzeLocalBandingTiles(width, height, config, rowTileReader(width, readRow))

internal fun analyzeLocalBandingTiles(width: Int, height: Int, config: DetectionConfig,
    readTile: PixelTileReader): LocalBandingEvidence? {
    if (width < 256 || height < 256) return null
    // Preserve the established coarse result. A smaller fallback can fit inside a small screen
    // without including its bezel, surrounding room, and several unrelated lines of text.
    for (size in listOf(128, 64, 256)) {
        if (width < size || height < size + size/4) continue
        val evidence = analyzeLocalScale(width, height, size, config, readTile)
        if (evidence != null) return evidence
    }
    return null
}

private fun analyzeLocalScale(width: Int, height: Int, size: Int, config: DetectionConfig,
    readTile: PixelTileReader): LocalBandingEvidence? {
    if (size / config.minCycles < config.minPeriodRows) return null
    val margin = size / 8
    val regionWidth = size / 4
    val pixels = IntArray(size * (size + 2 * margin))
    val candidates = mutableListOf<LocalStripeTile>()
    for (gy in 0..3) for (gx in 0..5) {
        val left = ((width - size) * (gx + 0.5) / 6).toInt()
        val top = margin + ((height - size - 2*margin) * (gy + 0.5) / 4).toInt()
        val values = DoubleArray(size * (size+2*margin))
        readTile(left, top-margin, size, size+2*margin, pixels)
        for (y in 0 until size+2*margin) {
            for (x in 0 until size) {
                val pixel = pixels[y*size+x]
                values[y*size+x] = if (pixel ushr 24 == 255) pixelLuminance(pixel) else Double.NaN
            }
        }
        var best: LocalStripeTile? = null
        var bestCoherentAmplitude = 0.0
        for (slopeIndex in -4..4) {
            val slope = slopeIndex * 0.05
            val profiles = List(4) { DoubleArray(size) }
            var usable = true
            for (y in 0 until size) for (region in 0..3) {
                val samples = DoubleArray(regionWidth) { offset ->
                    val x = region*regionWidth+offset
                    val yy = y+margin+(slope*(x-(size-1)/2.0)).roundToInt()
                    values[yy*size+x]
                }
                if (samples.any { !it.isFinite() }) { usable = false; break }
                samples.sort()
                // Upper quartile suppresses sparse dark glyphs while retaining broad dark bands.
                profiles[region][y] = samples[regionWidth*3/4]
            }
            if (!usable) continue
            // Suppress pixel grain in the narrow-column quantiles before testing recurrence.
            val filtered = profiles.map { profile -> DoubleArray(size) { y ->
                (profile[maxOf(0,y-1)] + 2*profile[y] + profile[minOf(size-1,y+1)]) / 4
            } }
            val result = analyzeBandingProfiles(filtered, List(4) { regionWidth }, config)
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
            // Correlation alone is almost identical at several aliased slopes in a small window.
            // Prefer the tilt retaining the strongest recurring brightness, rather than a nearly
            // flat projection whose tiny residual happens to have a slightly higher correlation.
            val coherentAmplitude = result.rmsAmplitude * result.autocorrelation
            if (best == null || coherentAmplitude > bestCoherentAmplitude) {
                best = LocalStripeTile(left, top, size, period,
                    slope, result.autocorrelation, gx, gy, sourceMarginRows = margin)
                bestCoherentAmplitude = coherentAmplitude
            }
        }
        best?.let { candidates += it }
    }
    // A repeated structure must agree in spacing and slope across several spatial positions.
    val cluster = candidates.mapNotNull { anchor ->
        val samePeriod = candidates.filter {
            abs(it.periodRows-anchor.periodRows) <= maxOf(1.0, anchor.periodRows*0.2)
        }
        val aligned = samePeriod.filter {
            abs(it.slope-anchor.slope) <= 0.1+1e-9
        }
        aligned.takeIf { it.size >= 4 && it.map { tile -> tile.row }.distinct().size >= 2 &&
            hasPersistentColumns(it, samePeriod, config.minRegionSupport) }
    }.maxByOrNull { it.size }
    return cluster?.let { LocalBandingEvidence(it) }
}

/** A line family must persist vertically at more than one horizontal position.
 * Counting unrelated rows/columns alone lets converging physical slats assemble a false cluster.
 * One slope search step is allowed between rows to account for the quantized tilt estimate.
 */
private fun hasPersistentColumns(tiles: List<LocalStripeTile>, samePeriod: List<LocalStripeTile>,
    minimumSupport: Double): Boolean {
    // Inspect the period-compatible evidence before filtering by tilt. Otherwise discarding a
    // contradictory row could make converging slats look like several independent stable columns.
    val repeatedColumns = samePeriod.groupBy { it.column }.filterValues { it.size >= 2 }
    if (repeatedColumns.size < 2) return false
    val persistent = repeatedColumns.filterValues { column ->
        column.indices.all { i -> (i+1 until column.size).all { j ->
            val first=column[i]; val second=column[j]
            abs(first.slope-second.slope) <= 0.05+1e-9 &&
                abs(first.periodRows-second.periodRows) <= maxOf(1.0,minOf(first.periodRows,second.periodRows)*0.2)
        } }
    }.keys
    return persistent.size.toDouble()/repeatedColumns.size >= minimumSupport &&
        tiles.groupBy { it.column }.filter { (column, evidence) -> column in persistent && evidence.size >= 2 }
            .values.let { columns ->
                // Grid indices alone do not establish independent support: large windows can
                // overlap almost completely in a narrow preview. Require separated source
                // windows along both axes; adjacent rows/columns may share at most one third of their pixels.
                columns.any { first -> columns.any { second ->
                    kotlin.math.abs(first.first().x-second.first().x) >= first.first().size*2.0/3 &&
                        first.maxOf { it.y }-first.minOf { it.y } >= first.first().size*2.0/3 &&
                        second.maxOf { it.y }-second.minOf { it.y } >= second.first().size*2.0/3
                } }
            }
}
