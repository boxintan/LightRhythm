package com.example.lightrhythm

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoStabilityTest {
    @Test fun largeImagesAndRepeatedSwitching() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = mutableListOf<File>()
        try {
            for ((index, color) in listOf(Color.RED, Color.BLUE).withIndex()) {
                val file = File.createTempFile("stability_$index", ".png", context.cacheDir)
                files.add(file)
                val source = Bitmap.createBitmap(if (index == 0) 6000 else 3000, if (index == 0) 4000 else 5000, Bitmap.Config.ARGB_8888)
                try {
                    source.eraseColor(color)
                    file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { source.recycle() }
            }
            repeat(20) { iteration ->
                val index = iteration % 2
                val preview = decodePhotoPreview(context, Uri.fromFile(files[index]))
                try {
                    assertTrue(maxOf(preview.width, preview.height) <= 1600)
                    assertEquals(if (index == 0) Color.RED else Color.BLUE, preview.getPixel(0, 0))
                    assertTrue(preview.allocationByteCount <= 1600 * 1600 * 4)
                } finally { preview.recycle() }
            }
        } finally { files.forEach { it.delete() } }
    }

    @Test fun repeatedPlaceholderProcessingAndInvalidStrength() = runBlocking {
        val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            val pipeline = PhotoProcessingPipeline()
            repeat(100) {
                assertEquals(ProcessingResult.NotIntegrated, pipeline.run(image, 1f).result)
                assertFalse(image.isRecycled)
            }
            for (strength in listOf(-1f, 2f, Float.NaN)) {
                try {
                    pipeline.run(image, strength)
                    fail("Invalid strength accepted")
                } catch (_: IllegalArgumentException) { }
            }
        } finally { image.recycle() }
    }
}
