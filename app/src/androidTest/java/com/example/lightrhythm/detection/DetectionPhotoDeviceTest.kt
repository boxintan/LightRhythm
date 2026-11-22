package com.example.lightrhythm.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lightrhythm.model.BandingStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Opt-in regression with user-provided photos; no private images are bundled in the APK. */
@RunWith(AndroidJUnit4::class)
class DetectionPhotoDeviceTest {
    @Test fun detectsProvidedColorBandingExamplesAcrossPreviewSizes() {
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("detectionSampleDirectory")
        assumeTrue("Provide detectionSampleDirectory to run the private-photo regression", directory != null)
        for (name in listOf("01.jpg", "02.jpg")) {
            val file = File(directory!!, name)
            assertTrue("Missing sample $file", file.isFile)
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val original = BitmapFactory.decodeFile(file.path, options) ?: error("Cannot decode $file")
            try {
                for (edge in listOf(1920, 1600, 960, 480)) {
                    val ratio = minOf(1.0, edge.toDouble() / maxOf(original.width, original.height))
                    val width = maxOf(1, kotlin.math.round(original.width * ratio).toInt())
                    val height = maxOf(1, kotlin.math.round(original.height * ratio).toInt())
                    val bitmap = Bitmap.createScaledBitmap(original, width, height, true)
                    try {
                        val before = IntArray(width * height)
                        bitmap.getPixels(before, 0, width, 0, 0, width, height)
                        val start = System.nanoTime()
                        val result = detectBanding(bitmap)
                        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                        assertEquals("$name edge=$edge", BandingStatus.SUSPECTED_COLOR_BANDING, result.status)
                        val channelName = if (name == "01.jpg") "R-G" else "B-G"
                        val channel = result.colorEvidence!!.channels.first { it.channel == channelName }
                        assertEquals(BandingStatus.SUSPECTED, channel.status)
                        val expectedPeriod = (if (name == "01.jpg") 380.0 else 462.0) * height / 1440
                        assertTrue("$name period=${channel.candidatePeriodRows}",
                            abs(channel.candidatePeriodRows!! - expectedPeriod) <= expectedPeriod * .05)
                        val after = IntArray(before.size)
                        bitmap.getPixels(after, 0, width, 0, 0, width, height)
                        assertArrayEquals(before, after)
                        Log.i("LightRhythmDetection", "$name ${width}x$height status=${result.status} " +
                            "channel=$channelName period=${channel.candidatePeriodRows} support=${channel.regionSupport} " +
                            "analysisMs=$elapsedMs version=${result.algorithmVersion}")
                    } finally {
                        if (bitmap !== original) bitmap.recycle()
                    }
                }
            } finally { original.recycle() }
        }
    }
}
