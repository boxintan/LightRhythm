package com.example.lightrhythm.detection

import android.graphics.Bitmap
import kotlin.math.*

/** A candidate texture, not a diagnosis of its optical cause. Coordinates use input pixels. */
data class MoireTile(val x: Int, val y: Int, val size: Int, val channel: String,
    val dx: Int, val dy: Int, val recurrenceDisplacementPixels: Double, val correlation: Double)

data class MoireResult(val tiles: List<MoireTile>, val testedTiles: Int,
    val algorithmVersion: String = "local-chroma-dual-v5") {
    // Repeated chroma in several spatial cells is a cue, including straight color stripes.
    // A single image cannot establish whether those stripes belong to the source content.
    val isSuspected: Boolean get() = hasIndependentMoireSupport(tiles)
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
    val primary=analyzeMoireGrid(width,height,4,readTile)
    if(primary.isSuspected) return primary
    // An independent denser pass recovers small/curved color regions after nuisance rejection.
    // Do not pool votes across grids: an overlapping view of one texture is still one source.
    val secondary=analyzeMoireGrid(width,height,6,readTile)
    return (if(secondary.isSuspected) secondary else primary).copy(testedTiles=52)
}

private fun analyzeMoireGrid(width: Int,height: Int,grid: Int,readTile: PixelTileReader): MoireResult {
    val tiles = mutableListOf<MoireTile>()
    val directions = listOf(0 to 1, 1 to 0, 1 to 1, -1 to 1, 1 to 2, -1 to 2, 2 to 1, -2 to 1)
    for (gy in 0 until grid) for (gx in 0 until grid) {
      var cellBest: MoireTile? = null
      for (size in listOf(16, 32, 64, 128)) {
        // Small windows follow changing fringe curvature after preview downscaling.
        // Denser sampling avoids relying on a sparse sublattice of their few pixels.
        val sampleStep = when (size) { 16, 32 -> 1; 64 -> 2; else -> 3 }
        val left = ((width - size) * (gx + 0.5) / grid).toInt()
        val top = ((height - size) * (gy + 0.5) / grid).toInt()
        val channels = Array(3) { DoubleArray(size * size) { Double.NaN } }
        val absoluteChannels = Array(3) { DoubleArray(size * size) { Double.NaN } }
        var intensitySum = 0.0
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
                channels[2][y * size + x] = 255 * (r - b) / sum
                absoluteChannels[0][y*size+x]=(r-g).toDouble()
                absoluteChannels[1][y*size+x]=(b-g).toDouble()
                absoluteChannels[2][y*size+x]=(r-b).toDouble()
                intensitySum+=sum
                valid++
            }
        }
        if (valid < size * size * 0.65) continue
        val absoluteScale=255*valid/intensitySum
        var best: MoireTile? = null
        for ((channelIndex, values) in channels.withIndex()) {
            // Least-squares plane removes tint, shading and slow directional gradients.
            if(!removeSpatialColorPlane(values,size)) continue
            // A periodic denominator can turn a constant tint (R-G) into a periodic ratio.
            // Require recurrence in the absolute color difference as well. A single tile-mean
            // intensity puts it in comparable units without adding pixel-wise brightness waves.
            val absolute=absoluteChannels[channelIndex]
            for(i in absolute.indices) if(absolute[i].isFinite()) absolute[i]*=absoluteScale
            if(!removeSpatialColorPlane(absolute,size)) continue
            if(sqrt(values.sumOf { if(it.isFinite()) it*it else 0.0 }/valid)<1.5 ||
                sqrt(absolute.sumOf { if(it.isFinite()) it*it else 0.0 }/valid)<1.5) continue
            for ((dx, dy) in directions) {
                fun correlation(lag: Int, signal: DoubleArray=values): Double {
                    var ab = 0.0; var aa = 0.0; var bb = 0.0; var count = 0
                    for (y in 0 until size step sampleStep) for (x in 0 until size step sampleStep) {
                        val xx = x+dx*lag; val yy = y+dy*lag
                        if (xx !in 0 until size || yy !in 0 until size) continue
                        val va = signal[y*size+x]; val vb = signal[yy*size+xx]
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
                    if(correlation(lag,absolute)<.65 || correlation(lag/2,absolute)>-.25) continue
                    if (best == null || peak > best.correlation) best = MoireTile(left, top, size,
                        listOf("R-G","B-G","R-B")[channelIndex], dx, dy,
                        lag * sqrt((dx*dx+dy*dy).toDouble()), peak)
                }
            }
        }
        if (best != null && (cellBest == null || best.correlation > cellBest.correlation)) cellBest = best
      }
      cellBest?.let { tiles += it }
    }
    return MoireResult(tiles, grid*grid)
}

/** Raw and normalized color representations use the same finite-pixel mask and detrending. */
internal fun removeSpatialColorPlane(values: DoubleArray,size: Int): Boolean {
    val center=(size-1)/2.0
    var n=0.0; var sx=0.0; var sy=0.0; var sv=0.0
    var sxx=0.0; var syy=0.0; var sxy=0.0; var sxv=0.0; var syv=0.0
    for(y in 0 until size) for(x in 0 until size) {
        val value=values[y*size+x]
        if(!value.isFinite()) continue
        val xx=x-center; val yy=y-center
        n++; sx+=xx; sy+=yy; sv+=value
        sxx+=xx*xx; syy+=yy*yy; sxy+=xx*yy; sxv+=xx*value; syv+=yy*value
    }
    if(n<3) return false
    val a=sxx-sx*sx/n; val b=sxy-sx*sy/n; val c=syy-sy*sy/n
    val u=sxv-sx*sv/n; val v=syv-sy*sv/n
    val determinant=a*c-b*b
    if(determinant<=1e-9) return false
    val slopeX=(u*c-v*b)/determinant; val slopeY=(v*a-u*b)/determinant
    val intercept=(sv-slopeX*sx-slopeY*sy)/n
    for(y in 0 until size) for(x in 0 until size) {
        val i=y*size+x
        if(values[i].isFinite()) values[i]-=intercept+slopeX*(x-center)+slopeY*(y-center)
    }
    return true
}

/** Three grid cells whose pixel rectangles overlap are not three independent observations. */
internal fun hasIndependentMoireSupport(tiles: List<MoireTile>): Boolean {
    fun separate(a: MoireTile,b: MoireTile) = a.x+a.size<=b.x || b.x+b.size<=a.x ||
        a.y+a.size<=b.y || b.y+b.size<=a.y
    for(i in tiles.indices) for(j in i+1 until tiles.size) {
        if(!separate(tiles[i],tiles[j])) continue
        for(k in j+1 until tiles.size) if(separate(tiles[i],tiles[k]) && separate(tiles[j],tiles[k])) return true
    }
    return false
}
