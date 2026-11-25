package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PixelTilesTest {
    private val width = 512
    private val height = 384
    private fun pixel(x: Int, y: Int): Int {
        val wave = (18*sin(2*PI*(y/12.0+x*x/18000.0))).toInt()
        return (255 shl 24) or ((185+wave) shl 16) or (185 shl 8) or (185-wave)
    }
    private val rowReader: (Int, IntArray) -> Unit = { y, row ->
        for (x in row.indices) row[x] = pixel(x, y)
    }
    private fun tileReader(onRead: (Int) -> Unit = {}): PixelTileReader = { x, y, w, h, out ->
        assertTrue(x >= 0 && y >= 0 && x+w <= width && y+h <= height)
        assertEquals(w*h, out.size)
        onRead(w*h)
        for (yy in 0 until h) for (xx in 0 until w) out[yy*w+xx] = pixel(x+xx,y+yy)
    }

    @Test fun packedTilesPreserveEveryMoireCandidateAndReduceSourcePixelReads() {
        var rowPixels = 0
        val rows = analyzeMoire(width,height) { y, out -> rowPixels += out.size; rowReader(y,out) }
        var tilePixels = 0
        var tileCalls = 0
        val tiles = analyzeMoireTiles(width,height,tileReader { tilePixels += it; tileCalls++ })
        assertTrue(rows.isSuspected)
        assertEquals(rows,tiles)
        assertEquals(64,tileCalls)
        assertTrue("Only the required rectangles should be copied", tilePixels < rowPixels/4)
    }

    @Test fun packedTilesPreserveLocalBandingCoordinatesAndNumericalEvidence() {
        fun grayPixel(x: Int, y: Int): Int {
            val gray = (180+20*sin(2*PI*(y-.1*x)/12)).toInt()
            return (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val rows = analyzeLocalBanding(width,height,DetectionConfig()) { y, out ->
            for (x in out.indices) out[x] = grayPixel(x,y)
        }
        val tiles = analyzeLocalBandingTiles(width,height,DetectionConfig()) { x, y, w, h, out ->
            for (yy in 0 until h) for (xx in 0 until w) out[yy*w+xx] = grayPixel(x+xx,y+yy)
        }
        assertNotNull(rows)
        assertEquals(rows,tiles)
    }

    @Test fun rowAdapterCopiesTheRequestedOffsetAndStride() {
        val out = IntArray(15)
        rowTileReader(width,rowReader)(33,27,5,3,out)
        for (y in 0..2) for (x in 0..4) assertEquals(pixel(x+33,y+27),out[y*5+x])
    }
}
