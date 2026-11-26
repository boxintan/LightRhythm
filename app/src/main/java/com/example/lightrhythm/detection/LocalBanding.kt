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
    val values = DoubleArray(pixels.size)
    val samples = DoubleArray(regionWidth)
    val sourceOffsets = Array(9) { index ->
        val slope=(index-4)*0.05
        IntArray(size) { x -> (margin+(slope*(x-(size-1)/2.0)).roundToInt())*size+x }
    }
    val candidates = mutableListOf<LocalStripeTile>()
    for (gy in 0..3) for (gx in 0..5) {
        val left = ((width - size) * (gx + 0.5) / 6).toInt()
        val top = margin + ((height - size - 2*margin) * (gy + 0.5) / 4).toInt()
        var allOpaque=true
        readTile(left, top-margin, size, size+2*margin, pixels)
        for (y in 0 until size+2*margin) {
            for (x in 0 until size) {
                val pixel = pixels[y*size+x]
                values[y*size+x] = if (pixel ushr 24 == 255) pixelLuminance(pixel) else {
                    allOpaque=false
                    Double.NaN
                }
            }
        }
        var best: LocalStripeTile? = null
        var bestCoherentAmplitude = 0.0
        slopeLoop@ for (slopeIndex in -4..4) {
            val slope = slopeIndex * 0.05
            val offsets=sourceOffsets[slopeIndex+4]
            val profiles = List(4) { DoubleArray(size) }
            for (y in 0 until size) for (region in 0..3) {
                val rowStart=y*size
                if(allOpaque) for(offset in 0 until regionWidth) {
                    val x=region*regionWidth+offset
                    samples[offset]=values[rowStart+offsets[x]]
                } else for(offset in 0 until regionWidth) {
                    val x = region*regionWidth+offset
                    val value=values[rowStart+offsets[x]]
                    if(!value.isFinite()) continue@slopeLoop
                    samples[offset]=value
                }
                // Upper quartile suppresses sparse dark glyphs while retaining broad dark bands.
                profiles[region][y] = selectLuminanceOrderStatistic(samples,regionWidth*3/4)
            }
            // Suppress pixel grain in the narrow-column quantiles before testing recurrence.
            val filtered = profiles.map { profile -> DoubleArray(size) { y ->
                (profile[maxOf(0,y-1)] + 2*profile[y] + profile[minOf(size-1,y+1)]) / 4
            } }
            val raw=DoubleArray(size) { y ->
                var sum=0.0
                for(region in 0..3) sum+=filtered[region][y]*regionWidth.toDouble()/size
                sum
            }
            val maxLag=minOf(config.maxPeriodRows,size/config.minCycles)
            val trend=localLinearTrend(raw,maxLag)
            val residual=DoubleArray(size) { raw[it]-trend[it] }
            val amplitude=kotlin.math.sqrt(residual.sumOf { it*it }/size)
            if(amplitude<config.minRmsAmplitude) continue
            val candidate=selectBandingPeriod(residual,maxLag,config) ?: continue
            val period=candidate.period
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
            val coherentAmplitude = amplitude * candidate.correlation
            if (best == null || coherentAmplitude > bestCoherentAmplitude) {
                best = LocalStripeTile(left, top, size, period,
                    slope, candidate.correlation, gx, gy, sourceMarginRows = margin)
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

/** In-place exact selection for finite, nonnegative luminance samples. No quantization or sort.
 * The caller reuses its scratch array and overwrites every element before the next selection.
 */
internal fun selectLuminanceOrderStatistic(samples: DoubleArray,k: Int): Double {
    val ascending=samples.first()<=samples.last()
    var ordered=true
    for(i in 1 until samples.size) {
        if(if(ascending) samples[i]<samples[i-1] else samples[i]>samples[i-1]) { ordered=false; break }
    }
    if(ordered) return samples[if(ascending) k else samples.lastIndex-k]
    var left=0
    var right=samples.lastIndex
    while(left<right) {
        val a=samples[left]; val b=samples[(left+right) ushr 1]; val c=samples[right]
        val pivot=if(a<b) { if(b<c) b else maxOf(a,c) } else { if(a<c) a else maxOf(b,c) }
        var i=left; var j=right
        while(i<=j) {
            while(samples[i]<pivot) i++
            while(samples[j]>pivot) j--
            if(i<=j) {
                val value=samples[i]; samples[i]=samples[j]; samples[j]=value
                i++; j--
            }
        }
        if(k<=j) right=j else if(k>=i) left=i else return samples[k]
    }
    return samples[k]
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
