package com.example.lightrhythm.repair

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class BandingRepairTest {

    private class Image(val width: Int, val height: Int, val luma: FloatArray)

    private fun synthetic(
        width: Int,
        height: Int,
        axis: BandingAxis,
        period: Double,
        amplitude: Double,
        noise: Double = 0.03,
        seed: Long = 42L,
        scale: Double = 1.0,
    ): Image {
        val random = Random(seed)
        val luma = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gradient = 0.45 + 0.08 * y / height + 0.06 * x / width
                val texture = (random.nextDouble() - 0.5) * noise
                val coordinate = if (axis == BandingAxis.HORIZONTAL) y.toDouble() else x.toDouble()
                val band = if (amplitude > 0.0) 1.0 + amplitude * sin(2.0 * PI * coordinate / period) else 1.0
                val value = (gradient + texture) * band * scale
                luma[y * width + x] = value.toFloat().coerceIn(0f, 1f)
            }
        }
        return Image(width, height, luma)
    }

    /** Projects the linearly detrended mean profile onto the known banding period. */
    private fun measuredAmplitude(image: Image, axis: BandingAxis, period: Double): Double {
        val n = if (axis == BandingAxis.HORIZONTAL) image.height else image.width
        val profile = DoubleArray(n)
        if (axis == BandingAxis.HORIZONTAL) {
            for (y in 0 until image.height) {
                var sum = 0.0
                for (x in 0 until image.width) sum += image.luma[y * image.width + x]
                profile[y] = sum / image.width
            }
        } else {
            for (x in 0 until image.width) {
                var sum = 0.0
                for (y in 0 until image.height) sum += image.luma[y * image.width + x]
                profile[x] = sum / image.height
            }
        }

        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (i in 0 until n) {
            sumX += i
            sumY += profile[i]
            sumXX += i.toDouble() * i
            sumXY += i * profile[i]
        }
        val denominator = n * sumXX - sumX * sumX
        val slope = if (denominator == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        var projectionCos = 0.0
        var projectionSin = 0.0
        for (i in 0 until n) {
            val residual = profile[i] - (intercept + slope * i)
            val angle = 2.0 * PI * i / period
            projectionCos += residual * cos(angle)
            projectionSin += residual * sin(angle)
        }
        return 2.0 * sqrt(projectionCos * projectionCos + projectionSin * projectionSin) / n
    }

    @Test
    fun repairsHorizontalBanding() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val before = measuredAmplitude(image, BandingAxis.HORIZONTAL, 8.0)

        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertTrue("horizontal banding should be detected", outcome.applied)
        val estimate = outcome.estimate
        assertNotNull(estimate)
        assertEquals(BandingAxis.HORIZONTAL, estimate!!.axis)
        assertEquals(8.0, estimate.periodPx, 0.5)
        assertEquals(0.18, estimate.amplitude, 0.05)

        val after = measuredAmplitude(Image(image.width, image.height, outcome.luma), BandingAxis.HORIZONTAL, 8.0)
        assertTrue("residual $after should be far below $before", after < before * 0.25)
        assertEquals(image.luma.average(), outcome.luma.average(), 0.01)
    }

    @Test
    fun repairsVerticalBanding() {
        val image = synthetic(480, 640, BandingAxis.VERTICAL, period = 12.5, amplitude = 0.15)
        val before = measuredAmplitude(image, BandingAxis.VERTICAL, 12.5)

        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertTrue("vertical banding should be detected", outcome.applied)
        val estimate = outcome.estimate
        assertNotNull(estimate)
        assertEquals(BandingAxis.VERTICAL, estimate!!.axis)
        assertEquals(12.5, estimate.periodPx, 0.8)

        val after = measuredAmplitude(Image(image.width, image.height, outcome.luma), BandingAxis.VERTICAL, 12.5)
        assertTrue("residual $after should be far below $before", after < before * 0.25)
    }

    @Test
    fun handlesNonIntegerPeriod() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 7.4, amplitude = 0.16)
        val before = measuredAmplitude(image, BandingAxis.HORIZONTAL, 7.4)

        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertTrue(outcome.applied)
        val estimate = outcome.estimate
        assertNotNull(estimate)
        assertEquals(7.4, estimate!!.periodPx, 0.5)

        val after = measuredAmplitude(Image(image.width, image.height, outcome.luma), BandingAxis.HORIZONTAL, 7.4)
        assertTrue("residual $after should be far below $before", after < before * 0.3)
    }

    @Test
    fun leavesSharpHorizonUntouched() {
        // A hard horizontal edge is broadband, not periodic; it must not be "repaired".
        val width = 640
        val height = 480
        val random = Random(7L)
        val luma = FloatArray(width * height)
        for (y in 0 until height) {
            val base = if (y < height / 2) 0.25 else 0.75
            for (x in 0 until width) {
                val texture = (random.nextDouble() - 0.5) * 0.04
                luma[y * width + x] = (base + texture).toFloat().coerceIn(0f, 1f)
            }
        }

        val outcome = BandingRepair.repair(luma, width, height)

        assertFalse("a horizon edge must not be mistaken for banding", outcome.applied)
        assertArrayEquals(luma, outcome.luma, 0f)
    }

    @Test
    fun leavesNaturalTextureUntouched() {
        // Smooth random blobs (upsampled coarse grid) plus film grain: no periodic component.
        val width = 640
        val height = 480
        val random = Random(11L)
        val gridW = 16
        val gridH = 12
        val grid = DoubleArray(gridW * gridH) { 0.35 + random.nextDouble() * 0.3 }
        val luma = FloatArray(width * height)
        for (y in 0 until height) {
            val gy = y.toDouble() / (height - 1) * (gridH - 1)
            val y0 = gy.toInt().coerceAtMost(gridH - 2)
            val fy = gy - y0
            for (x in 0 until width) {
                val gx = x.toDouble() / (width - 1) * (gridW - 1)
                val x0 = gx.toInt().coerceAtMost(gridW - 2)
                val fx = gx - x0
                val top = grid[y0 * gridW + x0] * (1 - fx) + grid[y0 * gridW + x0 + 1] * fx
                val bottom = grid[(y0 + 1) * gridW + x0] * (1 - fx) + grid[(y0 + 1) * gridW + x0 + 1] * fx
                val value = top * (1 - fy) + bottom * fy + (random.nextDouble() - 0.5) * 0.03
                luma[y * width + x] = value.toFloat().coerceIn(0f, 1f)
            }
        }

        val outcome = BandingRepair.repair(luma, width, height)

        assertFalse("smooth texture must not be treated as banding", outcome.applied)
        assertArrayEquals(luma, outcome.luma, 0f)
    }

    @Test
    fun leavesCleanImageUntouched() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.0)
        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertFalse("clean image must not be modified", outcome.applied)
        assertArrayEquals(image.luma, outcome.luma, 0f)
    }

    @Test
    fun zeroStrengthIsNoOp() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val outcome = BandingRepair.repair(image.luma, image.width, image.height, strength = 0f)

        assertFalse(outcome.applied)
        assertArrayEquals(image.luma, outcome.luma, 0f)
    }

    @Test
    fun halfStrengthCorrectsLessThanFullStrength() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val before = measuredAmplitude(image, BandingAxis.HORIZONTAL, 8.0)

        val half = BandingRepair.repair(image.luma, image.width, image.height, strength = 0.5f)
        val halfResidual = measuredAmplitude(Image(image.width, image.height, half.luma), BandingAxis.HORIZONTAL, 8.0)

        assertTrue(half.applied)
        assertTrue("half strength should still reduce banding", halfResidual < before * 0.6)
        assertTrue("half strength should not over-correct", halfResidual > before * 0.2)
    }

    @Test
    fun usesSuppliedEstimate() {
        val image = synthetic(640, 480, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val before = measuredAmplitude(image, BandingAxis.HORIZONTAL, 8.0)
        val supplied = BandingEstimate(BandingAxis.HORIZONTAL, 8.0, 0.18, confidence = 100.0)

        val outcome = BandingRepair.repair(image.luma, image.width, image.height, supplied = supplied)

        assertTrue(outcome.applied)
        val after = measuredAmplitude(Image(image.width, image.height, outcome.luma), BandingAxis.HORIZONTAL, 8.0)
        assertTrue("residual $after should be far below $before", after < before * 0.25)
    }

    @Test
    fun doesNotMutateInputAndReturnsNewArray() {
        val image = synthetic(320, 240, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val snapshot = image.luma.copyOf()

        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertArrayEquals(snapshot, image.luma, 0f)
        assertFalse("output must be a new array", outcome.luma === image.luma)
    }

    @Test
    fun survivesDarkImageWithoutInvalidPixels() {
        val image = synthetic(320, 240, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18, scale = 0.02)
        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertTrue(outcome.luma.all { it.isFinite() && it >= 0f })
    }

    @Test
    fun handlesTinyImage() {
        val image = synthetic(8, 8, BandingAxis.HORIZONTAL, period = 8.0, amplitude = 0.18)
        val outcome = BandingRepair.repair(image.luma, image.width, image.height)

        assertFalse("image too small to analyse", outcome.applied)
        assertArrayEquals(image.luma, outcome.luma, 0f)
    }

    @Test
    fun largePreviewStaysFast() {
        val image = synthetic(1600, 1200, BandingAxis.HORIZONTAL, period = 9.0, amplitude = 0.15)
        val started = System.nanoTime()
        val outcome = BandingRepair.repair(image.luma, image.width, image.height)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0

        assertTrue(outcome.applied)
        assertTrue("repair took ${elapsedMs}ms", elapsedMs < 3000.0)
    }
}
