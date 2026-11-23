package com.example.lightrhythm.detection

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lightrhythm.model.BandingStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class BitmapDetectionContractTest {
    @Test fun detectsColorWavesAtPreviewResolutionsWithoutChangingPixels() {
        for (height in listOf(360, 720, 1200, 1440)) {
            val width = 65
            val period = 380.0 * height / 1440
            val pixels = IntArray(width * height) { index ->
                val wave = 24 * sin(2 * PI * (index / width) / period)
                (255 shl 24) or ((128 + wave).toInt() shl 16) or
                    ((128 - .299 / .587 * wave).toInt() shl 8) or 128
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                val result = detectBanding(bitmap)
                assertEquals("height=$height", BandingStatus.SUSPECTED_COLOR_BANDING, result.status)
                val color = result.colorEvidence!!.channels.first { it.channel == "R-G" }
                assertTrue(abs(color.candidatePeriodRows!! - period) <= period * .05)
                assertTrue(result.candidateRowOffsets.isEmpty())
                val after = IntArray(pixels.size)
                bitmap.getPixels(after, 0, width, 0, 0, width, height)
                assertArrayEquals(pixels, after)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun smoothColorGradientIsNotBanding() {
        val width = 65
        val height = 720
        val pixels = IntArray(width * height) { index ->
            val red = 70 + (index / width) / 6
            (255 shl 24) or (red shl 16) or (120 shl 8) or 100
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { assertFalse(detectBanding(bitmap).isSuspected) } finally { bitmap.recycle() }
    }

    @Test fun transparentBitmapIsRejected() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try { expectInvalidInput { detectBanding(bitmap) } } finally { bitmap.recycle() }
    }

    @Test fun recycledBitmapIsRejected() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        expectInvalidInput { detectBanding(bitmap) }
    }

    @Test fun hardwareBitmapIsRejectedBeforeReadingPixels() {
        assumeTrue("ImageDecoder requires API 28", Build.VERSION.SDK_INT >= 28)
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff808080.toInt())
        val encoded = ByteArrayOutputStream()
        try { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, encoded)) } finally { source.recycle() }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded.toByteArray()))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        }
        try {
            assertEquals(Bitmap.Config.HARDWARE, bitmap.config)
            expectInvalidInput { detectBanding(bitmap) }
        } finally { bitmap.recycle() }
    }

    @Test fun smallOpaqueBitmapIsInsufficientData() {
        val bitmap = Bitmap.createBitmap(33, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff808080.toInt())
        try { assertEquals(BandingStatus.INSUFFICIENT_DATA, detectBanding(bitmap).status) } finally { bitmap.recycle() }
    }

    private fun expectInvalidInput(action: () -> Unit) {
        try { action(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
