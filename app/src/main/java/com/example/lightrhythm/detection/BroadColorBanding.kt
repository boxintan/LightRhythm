package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BroadColorEvidence

/** Requires a caller-confirmed neutral source; does not identify a projector or infer cause. */
internal fun analyzeBroadColorBanding(
    width: Int, height: Int, readRow: (Int, IntArray) -> Unit,
): BroadColorEvidence? {
    if (width < 64 || height < 128) return null
    val bins = 32
    val channels = List(2) { List(4) { DoubleArray(bins) { Double.NaN } } }
    val pixels = IntArray(width)
    val stepX = maxOf(1, width / 320)
    val stepY = maxOf(1, height / 320)
    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
    for (bin in 0 until bins) {
        val rg = List(4) { ArrayList<Double>() }
        val bg = List(4) { ArrayList<Double>() }
        val totals = IntArray(4)
        for (y in (height * bin / bins) until (height * (bin + 1) / bins) step stepY) {
            readRow(y, pixels)
            for (region in 0..3) {
                val left = (width * (.1 + region * .2)).toInt()
                val right = (width * (.1 + (region + 1) * .2)).toInt()
                for (x in left until right step stepX) {
                    totals[region]++
                    val pixel = pixels[x]
                    val r = (pixel ushr 16) and 255
                    val g = (pixel ushr 8) and 255
                    val b = pixel and 255
                    if ((pixel ushr 24) != 255 || minOf(r, g, b) < 40 ||
                        .299 * r + .587 * g + .114 * b < 90 || maxOf(r, g, b) - minOf(r, g, b) > 120) continue
                    val sum = (r + g + b).toDouble()
                    rg[region].add(255 * (r - g) / sum)
                    bg[region].add(255 * (b - g) / sum)
                }
            }
        }
        for (region in 0..3) if (rg[region].size >= 8 && rg[region].size >= totals[region] * .35) {
            channels[0][region][bin] = median(rg[region])
            channels[1][region][bin] = median(bg[region])
        }
    }
    fun spread(values: DoubleArray): Double {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.size < 12) return 0.0
        return sorted[((sorted.size - 1) * .9).toInt()] - sorted[((sorted.size - 1) * .1).toInt()]
    }
    return channels.mapIndexedNotNull { index, profiles ->
        val consensus = DoubleArray(bins) { bin ->
            val values = profiles.map { it[bin] }.filter { it.isFinite() }
            if (values.size >= 3) median(values) else Double.NaN
        }
        val valid = consensus.count { it.isFinite() }
        if (valid < 16) return@mapIndexedNotNull null
        // A broad shift must persist across bins, not just one thin colored line.
        val smooth = DoubleArray(bins) { bin ->
            val values = (maxOf(0, bin - 1)..minOf(bins - 1, bin + 1)).map { consensus[it] }.filter { it.isFinite() }
            if (consensus[bin].isFinite() && values.size >= 2) values.average() else Double.NaN
        }
        val amplitude = spread(smooth)
        val support = profiles.count { profile ->
            val pairs = (0 until bins).filter { smooth[it].isFinite() && profile[it].isFinite() }
            pairs.size >= 16 && spread(profile) >= 8 &&
                correlation(pairs.map { profile[it] }.toDoubleArray(), pairs.map { smooth[it] }.toDoubleArray()) >= .75
        }
        if (amplitude >= 12 && support >= 3)
            BroadColorEvidence(if (index == 0) "R-G" else "B-G", amplitude, support / 4.0, valid / bins.toDouble()) else null
    }.maxByOrNull { it.chromaSpread }
}
