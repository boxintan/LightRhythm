package com.example.lightrhythm

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BandingPhotoProcessorTest {
    @Test fun repairsGrayBandsAndPreservesOriginalPixels() = runBlocking {
        val width = 65
        val height = 512
        val pixels = IntArray(width * height) { i ->
            val gray = (128 * (1 + .15 * sin(2 * PI * (i / width) / 16))).toInt()
            (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        var output: Bitmap? = null
        try {
            val result = PhotoProcessingPipeline(BandingPhotoProcessor).run(bitmap, 1f).result
            assertTrue(result is ProcessingResult.Preview)
            output = (result as ProcessingResult.Preview).bitmap
            assertNotSame(bitmap, output)
            val after = IntArray(pixels.size)
            bitmap.getPixels(after, 0, width, 0, 0, width, height)
            assertArrayEquals(pixels, after)
            val corrected = IntArray(pixels.size)
            output.getPixels(corrected, 0, width, 0, 0, width, height)
            fun variation(values: IntArray): Double {
                val mean = values.map { (it and 255).toDouble() }.average()
                return values.sumOf { val delta = (it and 255) - mean; delta * delta } / values.size
            }
            assertTrue(variation(corrected) < variation(pixels) * .2)
        } finally { output?.recycle(); bitmap.recycle() }
    }

    @Test fun normalImageAndZeroStrengthDoNotClaimRepair() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff808080.toInt())
        try {
            assertTrue(BandingPhotoProcessor.process(bitmap, 1f) is ProcessingResult.NotApplied)
            assertTrue(BandingPhotoProcessor.process(bitmap, 0f) is ProcessingResult.NotApplied)
        } finally { bitmap.recycle() }
    }

    @Test fun colorBandsAreExplicitlyUnsupported() = runBlocking {
        val width = 65
        val pixels = IntArray(width * 720) { i ->
            val wave = 24 * sin(2 * PI * (i / width) / 190)
            (255 shl 24) or ((128 + wave).toInt() shl 16) or ((128 - .299 / .587 * wave).toInt() shl 8) or 128
        }
        val bitmap = Bitmap.createBitmap(pixels, width, 720, Bitmap.Config.ARGB_8888)
        try {
            val result = BandingPhotoProcessor.process(bitmap, 1f)
            assertTrue(result is ProcessingResult.NotApplied)
            assertTrue((result as ProcessingResult.NotApplied).message.contains("颜色横带"))
        } finally { bitmap.recycle() }
    }
}
