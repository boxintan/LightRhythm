package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.DetectionConfig
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class BandingAnalysisTest {
    private fun imageRows(height: Int = 512, value: (region: Int, y: Int) -> Double) =
        List(4) { region -> DoubleArray(height) { y -> value(region, y) } }

    private fun analyze(rows: List<DoubleArray>) = analyzeBandingProfiles(rows, List(4) { 32 })

    @Test
    fun constantImageHasNoBandingEvidence() {
        val result = analyze(imageRows { _, _ -> 128.0 })
        assertEquals(BandingStatus.LOW_AMPLITUDE, result.status)
        assertEquals(0.0, result.score, 1e-12)
        assertNull(result.candidatePeriodRows)
        assertTrue(result.candidateRowOffsets.isEmpty())
        assertTrue(result.rowResidual.all { abs(it) < 1e-9 })
    }

    @Test
    fun linearLightingGradientDoesNotBecomeBandsAtImageBoundaries() {
        val result = analyze(imageRows { _, y -> 30.0 + 0.35 * y })
        assertFalse(result.isSuspected)
        assertTrue(result.rowResidual.all { abs(it) < 1e-8 })
        assertEquals(30.0, result.rowTrend.first(), 1e-8)
        assertEquals(30.0 + 0.35 * 511, result.rowTrend.last(), 1e-8)
    }

    @Test
    fun detectsRepeatedBandsOverAGradientAndPreservesCoordinatesAndInput() {
        val rows = imageRows { region, y -> 50.0 + region * 8 + y * 0.15 + 18.0 * sin(2 * PI * y / 16) }
        val copy = rows.map { it.copyOf() }
        val result = analyze(rows)
        assertTrue(result.isSuspected)
        assertEquals(16, result.candidatePeriodRows)
        assertEquals(1.0, result.regionSupport, 0.0)
        assertTrue(result.score in 0.0..1.0)
        assertTrue(result.score > 0.8)
        assertEquals(128, result.imageWidth)
        assertEquals(512, result.imageHeight)
        assertEquals(512, result.candidateRowOffsets.size)
        assertTrue(result.candidateRowOffsets[4] > 0)
        assertTrue(result.candidateRowOffsets[12] < 0)
        for (y in 0 until 512) {
            assertEquals(result.rowLuminance[y], result.rowTrend[y] + result.rowResidual[y], 1e-9)
        }
        rows.indices.forEach { assertArrayEquals(copy[it], rows[it], 0.0) }
    }

    @Test
    fun handlesSeveralPeriodsInsteadOfReturningOneFixedPeriod() {
        for (period in listOf(8, 20, 32, 48)) {
            val result = analyze(imageRows { _, y -> 120.0 + 20.0 * sin(2 * PI * y / period) })
            assertTrue("period=$period, status=${result.status}", result.isSuspected)
            assertEquals(period, result.candidatePeriodRows)
        }
    }

    @Test
    fun veryWeakPeriodicVariationDoesNotTriggerHighRisk() {
        val result = analyze(imageRows { _, y -> 128.0 + 0.5 * sin(2 * PI * y / 16) })
        assertEquals(BandingStatus.LOW_AMPLITUDE, result.status)
        assertFalse(result.isSuspected)
    }

    @Test
    fun singleDarkHorizontalLineIsNotRepeatedBanding() {
        val result = analyze(imageRows { _, y -> if (y in 200..207) 30.0 else 150.0 })
        assertFalse(result.isSuspected)
        assertTrue(result.candidateRowOffsets.isEmpty())
    }

    @Test
    fun oneLightingStepIsNotRepeatedBanding() {
        val result = analyze(imageRows { _, y -> if (y < 256) 70.0 else 160.0 })
        assertFalse(result.isSuspected)
    }

    @Test
    fun smoothlyCurvedLightingIsNotRepeatedBanding() {
        val result = analyze(imageRows { _, y -> 70.0 + 100.0 * (y / 511.0) * (y / 511.0) })
        assertFalse(result.isSuspected)
    }

    @Test
    fun localRepeatedTextLikeRowsLackCrossRegionSupport() {
        val result = analyze(imageRows { region, y ->
            if (region == 0 && y % 20 < 5) 40.0 else 150.0
        })
        assertEquals(BandingStatus.INCONSISTENT_REGIONS, result.status)
        assertEquals(0.25, result.regionSupport, 0.0)
        assertFalse(result.isSuspected)
        assertTrue(result.candidateRowOffsets.isEmpty())
    }

    @Test
    fun independentNoiseDoesNotBecomeAConfirmedPattern() {
        val random = Random(20261121)
        val result = analyze(imageRows { _, _ -> 128.0 + random.nextDouble(-18.0, 18.0) })
        assertFalse(result.isSuspected)
    }

    @Test
    fun threeConsistentRegionsAreReportedWithOneUnsupportedRegion() {
        val result = analyze(imageRows { region, y ->
            if (region == 3) 128.0 else 128.0 + 18.0 * sin(2 * PI * y / 16)
        })
        assertTrue(result.isSuspected)
        assertEquals(0.75, result.regionSupport, 0.0)
        assertFalse(result.regions.last().supportsCandidate)
    }

    @Test
    fun globalRepeatedSceneTextureRemainsAmbiguousRatherThanClaimingLedCause() {
        val result = analyze(imageRows { _, y -> if (y % 24 < 12) 90.0 else 160.0 })
        // 相同像素统计无法区分真实横纹与 LED 频闪；结果只能是疑似。
        assertEquals(BandingStatus.SUSPECTED, result.status)
        assertEquals("row-periodicity-v2", result.algorithmVersion)
    }

    @Test
    fun unevenRegionWidthsStillProducePixelWeightedWholeImageMean() {
        val rows = listOf(40.0, 80.0, 120.0, 200.0).map { value -> DoubleArray(64) { value } }
        val result = analyzeBandingProfiles(rows, listOf(8, 8, 8, 9))
        assertEquals(33, result.imageWidth)
        assertEquals((8 * 40.0 + 8 * 80.0 + 8 * 120.0 + 9 * 200.0) / 33, result.rowLuminance[0], 1e-10)
    }

    @Test
    fun insufficientHeightOrRegionWidthDoesNotReturnHealthyConclusion() {
        assertEquals(BandingStatus.INSUFFICIENT_DATA, analyze(imageRows(20) { _, _ -> 128.0 }).status)
        assertEquals(BandingStatus.INSUFFICIENT_DATA,
            analyzeBandingProfiles(imageRows { _, _ -> 128.0 }, listOf(1, 1, 1, 1)).status)
    }

    @Test
    fun pixelRegionsCoverOddImageWidthWithoutDroppingLastColumn() {
        val profiles = readRegionProfiles(33, 64) { _, pixels ->
            pixels.fill(0xff000000.toInt())
            pixels[32] = 0xffffffff.toInt()
        }
        assertEquals(listOf(8, 8, 8, 9), profiles.widths)
        val result = analyzeBandingProfiles(profiles.rows, profiles.widths)
        assertEquals(255.0 / 33, result.rowLuminance.first(), 1e-10)
    }

    @Test
    fun normalizedCorrelationHasKnownPhaseBehavior() {
        val signal = DoubleArray(64) { sin(2 * PI * it / 8) }
        assertEquals(1.0, correlation(signal, signal, 8), 1e-12)
        assertEquals(-1.0, correlation(signal, signal, 4), 1e-12)
        assertEquals(0.0, correlation(DoubleArray(64), DoubleArray(64)), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteProfile() {
        analyze(imageRows { _, _ -> Double.NaN })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedProfileHeights() {
        analyzeBandingProfiles(listOf(DoubleArray(64), DoubleArray(63)), listOf(32, 32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPeriodSearchConfiguration() {
        DetectionConfig(minPeriodRows = 16, maxPeriodRows = 8)
    }
}
