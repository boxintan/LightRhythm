package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.ColorDetectionConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class ColorResolutionTest {
    private fun profiles(height: Int, periodic: Boolean = true): DetectionProfiles {
        val period = 380.0 * height / 1440
        return readDetectionProfiles(65, height) { y, pixels ->
            val wave = if (periodic) 24 * sin(2 * PI * y / period) else -20 + 40.0 * y / (height - 1)
            val red = (128 + wave).toInt()
            val green = (128 - .299 / .587 * wave).toInt()
            pixels.fill((255 shl 24) or (red shl 16) or (green shl 8) or 128)
        }
    }

    @Test fun windowTracksImageHeightAndCanReproduceFixedWindow() {
        for (height in listOf(360, 720, 1200, 1440)) {
            assertEquals((256.0 * height / 1440).roundToInt(), effectiveColorTrendRadius(height, ColorDetectionConfig()))
            assertEquals(256, effectiveColorTrendRadius(height, ColorDetectionConfig(referenceHeightRows = null)))
        }
    }

    @Test fun colorPeriodAndOutputCoordinatesTrackInputResolution() {
        for (height in listOf(360, 720, 1200, 1440)) {
            val input = profiles(height)
            val result = analyzeDetectionProfiles(input)
            assertEquals("height=$height", BandingStatus.SUSPECTED_COLOR_BANDING, result.status)
            assertEquals(height, result.imageHeight)
            assertEquals(height, result.rowLuminance.size)
            assertTrue(result.candidateRowOffsets.isEmpty())
            val evidence = result.colorEvidence!!
            val channel = evidence.channels.first { it.channel == "R-G" }
            val expected = 380.0 * height / 1440
            assertTrue("height=$height period=${channel.candidatePeriodRows}",
                abs(channel.candidatePeriodRows!! - expected) <= expected * .05)
            assertEquals(effectiveColorTrendRadius(height, evidence.config), evidence.effectiveTrendRadiusRows)
            assertEquals(minOf(1024, height / 2), evidence.effectiveMaxLagRows)
        }
    }

    @Test fun resizingWindowDoesNotTurnSmoothColorChangesIntoBands() {
        for (height in listOf(360, 720, 1200, 1440)) {
            assertFalse("height=$height", analyzeDetectionProfiles(profiles(height, false)).isSuspected)
        }
    }

    @Test fun windowScalingHasFiniteBounds() {
        assertEquals(1, effectiveColorTrendRadius(1, ColorDetectionConfig(referenceHeightRows = Int.MAX_VALUE)))
        assertEquals(1024, effectiveColorTrendRadius(Int.MAX_VALUE, ColorDetectionConfig()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidReferenceHeight() {
        ColorDetectionConfig(referenceHeightRows = 0)
    }
}
