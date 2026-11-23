package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MinimumPeriodTest {
    private fun signal(period: Int) = List(4) { DoubleArray(512) { y -> 128 + 20 * sin(2 * PI * y / period) } }

    @Test fun subMinimumPatternsCannotEnterThroughLongerMultiples() {
        for (period in listOf(2, 3)) {
            val result = analyzeBandingProfiles(signal(period), List(4) { 32 })
            assertFalse("true period=$period, reported=${result.candidatePeriodRows}", result.isSuspected)
            assertTrue(result.candidateRowOffsets.isEmpty())
        }
    }

    @Test fun configuredMinimumAlsoExcludesAliases() {
        for (period in listOf(5, 6, 8)) {
            val result = analyzeBandingProfiles(signal(period), List(4) { 32 }, DetectionConfig(minPeriodRows = 16))
            assertFalse("true period=$period, reported=${result.candidatePeriodRows}", result.isSuspected)
        }
    }

    @Test fun allowedFundamentalsStillDetect() {
        for (period in listOf(4, 8, 16, 32)) {
            val result = analyzeBandingProfiles(signal(period), List(4) { 32 })
            assertTrue("period=$period", result.isSuspected)
            assertEquals(period, result.candidatePeriodRows)
        }
    }

    @Test fun colorChannelCannotBypassMinimumPeriodEither() {
        val profiles = readDetectionProfiles(65, 512) { y, pixels ->
            val wave = 24 * sin(2 * PI * y / 3)
            pixels.fill((255 shl 24) or ((128 + wave).toInt() shl 16) or
                ((128 - .299 / .587 * wave).toInt() shl 8) or 128)
        }
        assertFalse(analyzeDetectionProfiles(profiles).isSuspected)
    }
}
