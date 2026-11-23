package com.example.lightrhythm.detection

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class BitmapRowLuminanceTest {
    @Test
    fun detectorReadsPeriodicBitmapAndKeepsOriginalPixels() {
        val width = 65
        val height = 256
        val sourcePixels = IntArray(width * height) { index ->
            val y = index / width
            val gray = (128.0 + 24.0 * sin(2 * PI * y / 16)).toInt()
            (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val bitmap = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val result = detectBanding(bitmap)
            assertTrue(result.isSuspected)
            assertEquals(16, result.candidatePeriodRows)
            assertEquals(width, result.imageWidth)
            assertEquals(height, result.imageHeight)
            val after = IntArray(sourcePixels.size)
            bitmap.getPixels(after, 0, width, 0, 0, width, height)
            assertArrayEquals(sourcePixels, after)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun readsRealBitmapRowsWithoutChangingInput() {
        val sourcePixels = intArrayOf(
            0xff000000.toInt(), 0xff000000.toInt(),
            0xffffffff.toInt(), 0xffffffff.toInt(),
            0xff808080.toInt(), 0xff808080.toInt(),
        )
        val bitmap = Bitmap.createBitmap(sourcePixels, 2, 3, Bitmap.Config.ARGB_8888)
        try {
            assertArrayEquals(doubleArrayOf(0.0, 255.0, 128.0), rowLuminance(bitmap), 1e-9)
            val after = IntArray(sourcePixels.size)
            bitmap.getPixels(after, 0, 2, 0, 0, 2, 3)
            assertArrayEquals(sourcePixels, after)
        } finally {
            bitmap.recycle()
        }
    }
}
