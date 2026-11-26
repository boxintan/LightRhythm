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
    val workspaces=listOf(16,32,64,128).map { MoireWorkspace(it,directions) }
    for (gy in 0 until grid) for (gx in 0 until grid) {
      var cellBest: MoireTile? = null
      for (workspace in workspaces) {
        val size=workspace.size
        // Small windows follow changing fringe curvature after preview downscaling.
        // Denser sampling avoids relying on a sparse sublattice of their few pixels.
        val sampleStep = when (size) { 16, 32 -> 1; 64 -> 2; else -> 3 }
        val left = ((width - size) * (gx + 0.5) / grid).toInt()
        val top = ((height - size) * (gy + 0.5) / grid).toInt()
        val channels = workspace.channels
        val absoluteChannels = workspace.absoluteChannels
        var intensitySum = 0.0
        val pixels = workspace.pixels
        readTile(left, top, size, size, pixels)
        var valid = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val p = pixels[y * size + x]
                val r = (p ushr 16) and 255; val g = (p ushr 8) and 255; val b = p and 255
                if (p ushr 24 != 255 || minOf(r, g, b) < 50 || maxOf(r, g, b) - minOf(r, g, b) > 100) {
                    workspace.valid[y*size+x]=false
                    for(channel in 0..2) {
                        channels[channel][y*size+x]=Double.NaN
                        absoluteChannels[channel][y*size+x]=Double.NaN
                    }
                    continue
                }
                workspace.valid[y*size+x]=true
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
        val allFinite=valid==size*size
        if(!allFinite) workspace.pairs.reset(workspace.valid)
        val geometry=if(allFinite) workspace.fullGeometry else ColorPlaneGeometry(size,channels[0])
        val absoluteScale=255*valid/intensitySum
        var best: MoireTile? = null
        for ((channelIndex, values) in channels.withIndex()) {
            // Least-squares plane removes tint, shading and slow directional gradients.
            if(!geometry.remove(values,allFinite)) continue
            if(sqrt(values.sumOf { if(it.isFinite()) it*it else 0.0 }/valid)<1.5) continue
            // A periodic denominator can turn a constant tint (R-G) into a periodic ratio.
            // Require recurrence in the absolute color difference as well. A single tile-mean
            // intensity puts it in comparable units without adding pixel-wise brightness waves.
            val absolute=absoluteChannels[channelIndex]
            for(i in absolute.indices) if(absolute[i].isFinite()) absolute[i]*=absoluteScale
            if(!geometry.remove(absolute,allFinite)) continue
            if(sqrt(absolute.sumOf { if(it.isFinite()) it*it else 0.0 }/valid)<1.5) continue
            val recurrence=if(allFinite) MoireRecurrence(values,size,sampleStep) else null
            val rawRecurrence=if(allFinite) MoireRecurrence(absolute,size,sampleStep) else null
            for (directionIndex in directions.indices) {
                val (dx,dy)=directions[directionIndex]
                fun correlation(lag: Int, signal: DoubleArray=values): Double {
                    if(!allFinite) return workspace.pairs.correlation(directionIndex,lag,signal)
                    var ab = 0.0; var aa = 0.0; var bb = 0.0; var count = 0
                    val shiftX=dx*lag; val shiftY=dy*lag
                    val startX=((maxOf(0,-shiftX)+sampleStep-1)/sampleStep)*sampleStep
                    val startY=((maxOf(0,-shiftY)+sampleStep-1)/sampleStep)*sampleStep
                    val endX=minOf(size,size-shiftX); val endY=minOf(size,size-shiftY)
                    val displacement=shiftY*size+shiftX
                    for (y in startY until endY step sampleStep) {
                      val rowStart=y*size
                      if(allFinite) for (x in startX until endX step sampleStep) {
                        val index=rowStart+x
                        val va=signal[index]; val vb=signal[index+displacement]
                        ab+=va*vb; aa+=va*va; bb+=vb*vb
                      } else for (x in startX until endX step sampleStep) {
                        val index=rowStart+x
                        val va = signal[index]; val vb = signal[index+displacement]
                        if (!va.isFinite() || !vb.isFinite()) continue
                        ab += va*vb; aa += va*va; bb += vb*vb; count++
                      }
                    }
                    if(allFinite) count=((endY-startY+sampleStep-1)/sampleStep)*((endX-startX+sampleStep-1)/sampleStep)
                    return if (count >= maxOf(80, size*size/120) && aa*bb > 1e-9) ab/sqrt(aa*bb) else 0.0
                }
                val maxLag = (size / 3) / maxOf(abs(dx), abs(dy))
                val corr = DoubleArray(maxLag+2) { recurrence?.estimate(dx,dy,it) ?: correlation(it) }
                val eligible=BooleanArray(maxLag+2) { recurrence==null }
                if(recurrence!=null) {
                    val margin=1e-6
                    for(lag in 4..maxLag) eligible[lag]=corr[lag]>=.65-margin &&
                        corr[lag]>=corr[lag-1]-2*margin && corr[lag]>=corr[lag+1]-2*margin && corr[lag/2]<=-.25+margin
                    val refined=BooleanArray(corr.size)
                    for(lag in 4..maxLag) if(eligible[lag]) for(offset in intArrayOf(lag,lag-1,lag+1,lag/2)) {
                        if(!refined[offset]) { corr[offset]=recurrence.exact(dx,dy,offset); refined[offset]=true }
                    }
                }
                val absoluteCorr=DoubleArray(maxLag+2) { Double.NaN }
                fun rawCorrelation(lag: Int): Double {
                    if(absoluteCorr[lag].isNaN()) {
                        val approximate=rawRecurrence?.estimate(dx,dy,lag)
                        absoluteCorr[lag]=if(approximate!=null && abs(approximate-.65)>1e-6 && abs(approximate+.25)>1e-6)
                            approximate else correlation(lag,absolute)
                    }
                    return absoluteCorr[lag]
                }
                for (lag in 4..maxLag) {
                    if(!eligible[lag]) continue
                    val peak = corr[lag]
                    if (peak < 0.65 || peak < corr[lag-1] || peak <= corr[lag+1] || corr[lag/2] > -0.25) continue
                    if(rawCorrelation(lag)<.65 || rawCorrelation(lag/2)>-.25) continue
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
    return ColorPlaneGeometry(size,values).remove(values,false)
}

private class MoireWorkspace(val size: Int,directions: List<Pair<Int,Int>>) {
    val pixels=IntArray(size*size)
    val channels=Array(3) { DoubleArray(size*size) }
    val absoluteChannels=Array(3) { DoubleArray(size*size) }
    val valid=BooleanArray(size*size)
    val pairs=MoirePairCache(size,when(size) { 16,32 -> 1; 64 -> 2; else -> 3 },directions)
    val fullGeometry=ColorPlaneGeometry(size,channels[0])
}

/** The finite mask is shared by six channels. Cache its geometry, keeping arithmetic order. */
private class ColorPlaneGeometry(private val size: Int,mask: DoubleArray) {
    private val center=(size-1)/2.0
    private var n=0.0
    private var sx=0.0; private var sy=0.0
    private var sxx=0.0; private var syy=0.0; private var sxy=0.0
    init {
        for(y in 0 until size) for(x in 0 until size) {
            if(!mask[y*size+x].isFinite()) continue
            val xx=x-center; val yy=y-center
            n++; sx+=xx; sy+=yy
            sxx+=xx*xx; syy+=yy*yy; sxy+=xx*yy
        }
    }
    fun remove(values: DoubleArray,allFinite: Boolean): Boolean {
    if(n<3) return false
    var sv=0.0; var sxv=0.0; var syv=0.0
    for(y in 0 until size) {
        val yy=y-center
        for(x in 0 until size) {
            val value=values[y*size+x]
            if(!allFinite && !value.isFinite()) continue
            val xx=x-center
            sv+=value; sxv+=xx*value; syv+=yy*value
        }
    }
    val a=sxx-sx*sx/n; val b=sxy-sx*sy/n; val c=syy-sy*sy/n
    val u=sxv-sx*sv/n; val v=syv-sy*sv/n
    val determinant=a*c-b*b
    if(determinant<=1e-9) return false
    val slopeX=(u*c-v*b)/determinant; val slopeY=(v*a-u*b)/determinant
    val intercept=(sv-slopeX*sx-slopeY*sy)/n
    for(y in 0 until size) for(x in 0 until size) {
        val i=y*size+x
        if(allFinite || values[i].isFinite()) values[i]-=intercept+slopeX*(x-center)+slopeY*(y-center)
    }
    return true
    }
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
