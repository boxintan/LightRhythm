package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.ColorDetectionConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class ColorBandingAnalysisTest {
    private fun profiles(height: Int = 1440, color: (x: Int, y: Int) -> Int) =
        readDetectionProfiles(65, height) { y, pixels ->
            for (x in pixels.indices) pixels[x] = color(x, y)
        }

    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b

    // Opposite R/G modulation keeps encoded luminance approximately constant.
    private fun tint(y: Int, period: Int): Int {
        val wave = 24 * sin(2 * PI * y / period)
        return rgb((128 + wave).toInt(), (128 - .299 / .587 * wave).toInt(), 128)
    }

    @Test fun detectsLongColorPeriodsThatLuminanceBaselineMisses() {
        for (period in listOf(380, 462)) {
            val input = profiles { _, y -> tint(y, period) }
            val result = analyzeDetectionProfiles(input)
            assertEquals(BandingStatus.SUSPECTED_COLOR_BANDING, result.status)
            assertEquals(BandingStatus.LOW_AMPLITUDE, result.colorEvidence!!.luminanceStatus)
            assertTrue(result.isSuspected)
            val evidence = result.colorEvidence.channels.first { it.channel == "R-G" }
            assertTrue("expected about $period rows, got ${evidence.candidatePeriodRows}",
                kotlin.math.abs(period - evidence.candidatePeriodRows!!) <= period * .05)
            assertEquals(1.0, evidence.regionSupport, 0.0)
            assertTrue(result.candidateRowOffsets.isEmpty())
            assertNull(result.candidatePeriodRows)
        }
    }

    @Test fun featureCanBeDisabledToReproduceLuminanceBaseline() {
        val result = analyzeDetectionProfiles(profiles { _, y -> tint(y, 380) },
            colorConfig = ColorDetectionConfig(enabled = false))
        assertFalse(result.isSuspected)
        assertNull(result.colorEvidence)
        assertEquals("row-periodicity-v1", result.algorithmVersion)
    }

    @Test fun constantTintAndSmoothColorGradientDoNotTrigger() {
        for (gradient in listOf(false, true)) {
            val result = analyzeDetectionProfiles(profiles { _, y ->
                val red = if (gradient) 70 + y / 12 else 170
                rgb(red, 120, 100)
            })
            assertFalse(result.isSuspected)
            assertFalse(result.colorEvidence!!.isSuspected)
        }
    }

    @Test fun isolatedColorStripeDoesNotBecomeRepeatedBanding() {
        val result = analyzeDetectionProfiles(profiles { _, y ->
            if (y in 500..540) rgb(160, 110, 128) else rgb(128, 128, 128)
        })
        assertFalse(result.isSuspected)
    }

    @Test fun oneColorStepDoesNotBecomeRepeatedBanding() {
        val result = analyzeDetectionProfiles(profiles { _, y ->
            if (y < 720) rgb(150, 115, 128) else rgb(105, 140, 128)
        })
        assertFalse(result.isSuspected)
    }

    @Test fun veryWeakColorWavesStayBelowAmplitudeThreshold() {
        val result = analyzeDetectionProfiles(profiles { _, y ->
            val wave = sin(2 * PI * y / 380)
            rgb((128 + wave).toInt(), 128, (128 - wave).toInt())
        })
        assertFalse(result.isSuspected)
    }

    @Test fun independentRegionColorNoiseDoesNotBecomeBands() {
        val random = Random(20261122)
        val noise = List(4) { IntArray(1440) { random.nextInt(-20, 21) } }
        val result = analyzeDetectionProfiles(profiles { x, y ->
            val region = when { x < 16 -> 0; x < 32 -> 1; x < 48 -> 2; else -> 3 }
            val change = noise[region][y]
            rgb(128 + change, 128 - change / 2, 128)
        })
        assertFalse(result.isSuspected)
    }

    @Test fun oneRegionWithPeriodicColoredContentLacksSpatialSupport() {
        val result = analyzeDetectionProfiles(profiles { x, y ->
            if (x < 16) tint(y, 380) else rgb(128, 128, 128)
        })
        assertFalse(result.isSuspected)
        assertTrue(result.colorEvidence!!.channels.all { it.regionSupport <= .25 })
    }

    @Test fun grayBandingKeepsExistingLuminanceOutput() {
        val result = analyzeDetectionProfiles(profiles(256) { _, y ->
            val gray = (128 + 24 * sin(2 * PI * y / 16)).toInt()
            rgb(gray, gray, gray)
        })
        assertEquals(BandingStatus.SUSPECTED, result.status)
        assertEquals(16, result.candidatePeriodRows)
        assertEquals(256, result.candidateRowOffsets.size)
        assertFalse(result.colorEvidence!!.isSuspected)
    }

    @Test fun tinyInputIsSkippedAndReadCallbackRunsOncePerRow() {
        var calls = 0
        val data = readDetectionProfiles(33, 20) { _, pixels -> calls++; pixels.fill(rgb(120, 120, 120)) }
        assertEquals(20, calls)
        assertEquals(listOf(8, 8, 8, 9), data.luminance.widths)
        assertEquals(BandingStatus.INSUFFICIENT_DATA, analyzeDetectionProfiles(data).status)
    }

    @Test fun identicalColorSceneTextureRemainsAnAmbiguousCandidate() {
        // The method cannot infer whether these pixels came from a real striped surface.
        val result = analyzeDetectionProfiles(profiles { _, y -> tint(y, 380) })
        assertEquals(BandingStatus.SUSPECTED_COLOR_BANDING, result.status)
    }
}
