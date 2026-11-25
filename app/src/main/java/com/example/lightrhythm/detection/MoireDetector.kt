package com.example.lightrhythm.detection

import android.graphics.Bitmap
import kotlin.math.*

/** A candidate texture, not a diagnosis of its optical cause. Coordinates use input pixels. */
data class MoireTile(val x: Int, val y: Int, val size: Int, val channel: String,
    val dx: Int, val dy: Int, val recurrenceDisplacementPixels: Double, val correlation: Double)

data class MoireResult(val tiles: List<MoireTile>, val testedTiles: Int,
    val algorithmVersion: String = "local-chroma-recurrence-v4") {
    // Repeated chroma in several spatial cells is a cue, including straight color stripes.
    // A single image cannot establish whether those stripes belong to the source content.
    val isSuspected: Boolean get() = tiles.size >= 3
}

fun detectMoire(image: Bitmap): MoireResult {
    require(!image.isRecycled && image.config != Bitmap.Config.HARDWARE)
    return analyzeMoireTiles(image.width, image.height) { left, top, width, height, pixels ->
        image.getPixels(pixels, 0, width, left, top, width, height)
    }
}

/** Sixteen spatial cells, four native-resolution tile sizes; no full-image pixel copy. */
internal fun analyzeMoire(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): MoireResult {
    return analyzeMoireTiles(width, height, rowTileReader(width, readRow))
}

/** Reads only each required rectangle; Bitmap callers avoid thousands of full-width reads. */
internal fun analyzeMoireTiles(width: Int, height: Int, readTile: PixelTileReader): MoireResult {
    if (width < 256 || height < 256) return MoireResult(emptyList(), 0)
    val tiles = mutableListOf<MoireTile>()
    val directions = listOf(0 to 1, 1 to 0, 1 to 1, -1 to 1, 1 to 2, -1 to 2, 2 to 1, -2 to 1)
    for (gy in 0..3) for (gx in 0..3) {
      var cellBest: MoireTile? = null
      for (size in listOf(16, 32, 64, 128)) {
        // Small windows follow changing fringe curvature after preview downscaling.
        // Denser sampling avoids relying on a sparse sublattice of their few pixels.
        val sampleStep = when (size) { 16, 32 -> 1; 64 -> 2; else -> 3 }
        val center = (size - 1) / 2.0
        val left = ((width - size) * (gx + 0.5) / 4).toInt()
        val top = ((height - size) * (gy + 0.5) / 4).toInt()
        val channels = Array(2) { DoubleArray(size * size) { Double.NaN } }
        val pixels = IntArray(size * size)
        readTile(left, top, size, size, pixels)
        var valid = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val p = pixels[y * size + x]
                val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
                if (p ushr 24 != 255 || minOf(r, g, b) < 50 || maxOf(r, g, b) - minOf(r, g, b) > 100) continue
                val sum = (r + g + b).toDouble()
                channels[0][y * size + x] = 255 * (r - g) / sum
                channels[1][y * size + x] = 255 * (b - g) / sum
                valid++
            }
        }
        if (valid < size * size * 0.65) continue
        var best: MoireTile? = null
        for ((channelIndex, values) in channels.withIndex()) {
            // Least-squares plane removes tint, shading and slow directional gradients.
            var n = 0.0; var sx = 0.0; var sy = 0.0; var sv = 0.0
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sxv = 0.0; var syv = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                val v = values[y * size + x]; if (!v.isFinite()) continue
                val xx = x - center; val yy = y - center
                n++; sx += xx; sy += yy; sv += v
                sxx += xx*xx; syy += yy*yy; sxy += xx*yy; sxv += xx*v; syv += yy*v
            }
            val a = sxx - sx*sx/n; val b = sxy - sx*sy/n; val c = syy - sy*sy/n
            val u = sxv - sx*sv/n; val v = syv - sy*sv/n
            val determinant = a*c-b*b
            if (determinant <= 1e-9) continue
            val slopeX = (u*c-v*b)/determinant; val slopeY = (v*a-u*b)/determinant
            val intercept = (sv-slopeX*sx-slopeY*sy)/n
            var energy = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                val i = y*size+x
                if (values[i].isFinite()) {
                    values[i] -= intercept + slopeX*(x-center) + slopeY*(y-center)
                    energy += values[i]*values[i]
                }
            }
            if (sqrt(energy/n) < 1.5) continue
            for ((dx, dy) in directions) {
                fun correlation(lag: Int): Double {
                    var ab = 0.0; var aa = 0.0; var bb = 0.0; var count = 0
                    for (y in 0 until size step sampleStep) for (x in 0 until size step sampleStep) {
                        val xx = x+dx*lag; val yy = y+dy*lag
                        if (xx !in 0 until size || yy !in 0 until size) continue
                        val va = values[y*size+x]; val vb = values[yy*size+xx]
                        if (!va.isFinite() || !vb.isFinite()) continue
                        ab += va*vb; aa += va*va; bb += vb*vb; count++
                    }
                    return if (count >= maxOf(80, size*size/120) && aa*bb > 1e-9) ab/sqrt(aa*bb) else 0.0
                }
                val maxLag = (size / 3) / maxOf(abs(dx), abs(dy))
                val corr = DoubleArray(maxLag+2) { correlation(it) }
                for (lag in 4..maxLag) {
                    val peak = corr[lag]
                    if (peak < 0.65 || peak < corr[lag-1] || peak <= corr[lag+1] || corr[lag/2] > -0.25) continue
                    if (best == null || peak > best.correlation) best = MoireTile(left, top, size,
                        if (channelIndex == 0) "R-G" else "B-G", dx, dy,
                        lag * sqrt((dx*dx+dy*dy).toDouble()), peak)
                }
            }
        }
        if (best != null && (cellBest == null || best.correlation > cellBest.correlation)) cellBest = best
      }
      cellBest?.let { tiles += it }
    }
    return MoireResult(tiles, 16)
}
