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

    @Test fun colorBandsAreRepairedWithoutTouchingTheOriginal() = runBlocking {
        val width = 65
        val height = 720
        val pixels = IntArray(width * height) { i ->
            val wave = 24 * sin(2 * PI * (i / width) / 190)
            (255 shl 24) or ((128 + wave).toInt() shl 16) or ((128 - .299 / .587 * wave).toInt() shl 8) or 128
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        var output: Bitmap? = null
        try {
            val result = PhotoProcessingPipeline(BandingPhotoProcessor).run(bitmap, 1f).result
            assertTrue(result is ProcessingResult.Preview)
            output = (result as ProcessingResult.Preview).bitmap

            val after = IntArray(pixels.size)
            bitmap.getPixels(after, 0, width, 0, 0, width, height)
            assertArrayEquals(pixels, after)

            fun chromaVariation(values: IntArray): Double {
                val profile = (0 until height).map { y ->
                    var sum = 0.0
                    for (x in 0 until width) {
                        val p = values[y * width + x]
                        sum += (((p ushr 16) and 255) - ((p ushr 8) and 255)).toDouble()
                    }
                    sum / width
                }
                val mean = profile.average()
                return profile.sumOf { val d = it - mean; d * d } / height
            }
            val corrected = IntArray(pixels.size)
            output.getPixels(corrected, 0, width, 0, 0, width, height)
            assertTrue(chromaVariation(corrected) < chromaVariation(pixels) * .3)
        } finally { output?.recycle(); bitmap.recycle() }
    }
}
