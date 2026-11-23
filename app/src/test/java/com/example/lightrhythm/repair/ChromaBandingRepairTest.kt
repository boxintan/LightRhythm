package com.example.lightrhythm.repair

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ChromaBandingRepairTest {

    private class Rgb(val width: Int, val height: Int, val planes: Array<FloatArray>)

    /**
     * Builds banding with independent per-channel depth. Luma weights are 0.299/0.587/0.114;
     * passing depths that sum to zero leaves brightness perfectly flat, which is exactly the
     * projector case: visible colour bands, no brightness bands.
     */
    private fun build(
        width: Int,
        height: Int,
        axis: BandingAxis,
        period: Double,
        depths: DoubleArray,
        base: Double = 0.5,
        noise: Double = 0.0,
        seed: Long = 7L,
    ): Rgb {
        val random = Random(seed)
        val planes = Array(3) { FloatArray(width * height) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val coordinate = if (axis == BandingAxis.HORIZONTAL) y.toDouble() else x.toDouble()
                val wave = sin(2.0 * PI * coordinate / period)
                val index = y * width + x
                for (c in 0 until 3) {
                    val jitter = if (noise > 0.0) (random.nextDouble() - 0.5) * noise else 0.0
                    val value = base * (1.0 + depths[c] * wave) + jitter
                    planes[c][index] = value.toFloat().coerceIn(0f, 1f)
                }
            }
        }
        return Rgb(width, height, planes)
    }

    private fun profile(plane: FloatArray, width: Int, height: Int, axis: BandingAxis): DoubleArray {
        val n = if (axis == BandingAxis.HORIZONTAL) height else width
        val out = DoubleArray(n)
        if (axis == BandingAxis.HORIZONTAL) {
            for (y in 0 until height) {
                var sum = 0.0
                for (x in 0 until width) sum += plane[y * width + x]
                out[y] = sum / width
            }
        } else {
            for (x in 0 until width) {
                var sum = 0.0
                for (y in 0 until height) sum += plane[y * width + x]
                out[x] = sum / height
            }
        }
        return out
    }

    /** Amplitude of the sinusoid at [period] in the linearly detrended signal. */
    private fun amplitudeAt(signal: DoubleArray, period: Double): Double {
        val n = signal.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (i in 0 until n) {
            sumX += i
            sumY += signal[i]
            sumXX += i.toDouble() * i
            sumXY += i * signal[i]
        }
        val denominator = n * sumXX - sumX * sumX
        val slope = if (abs(denominator) < 1e-12) 0.0 else (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n
        var cosProj = 0.0
        var sinProj = 0.0
        for (i in 0 until n) {
            val residual = signal[i] - (intercept + slope * i)
            val angle = 2.0 * PI * i / period
            cosProj += residual * cos(angle)
            sinProj += residual * sin(angle)
        }
        return 2.0 * sqrt(cosProj * cosProj + sinProj * sinProj) / n
    }

    /** Luma depth that keeps brightness flat when paired with the given R and B depths. */
    private fun flatLumaDepths(redDepth: Double, blueDepth: Double): DoubleArray {
        val green = -(0.299 * redDepth + 0.114 * blueDepth) / 0.587
        return doubleArrayOf(redDepth, green, blueDepth)
    }

    private fun chromaLumaIsFlat(image: Rgb, axis: BandingAxis, period: Double) {
        val r = profile(image.planes[0], image.width, image.height, axis)
        val g = profile(image.planes[1], image.width, image.height, axis)
        val b = profile(image.planes[2], image.width, image.height, axis)
        val luma = DoubleArray(r.size) { 0.299 * r[it] + 0.587 * g[it] + 0.114 * b[it] }
        val lumaAmplitude = amplitudeAt(luma, period)
        assertTrue("test fixture must have flat luma, was $lumaAmplitude", lumaAmplitude < 0.001)
    }

    @Test
    fun repairsColorBandingWithProjectorPeriod() {
        // Mirrors the field capture: 391 px period on a 1600x1200 preview, brightness flat.
        val period = 391.0
        val image = build(400, 1200, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15), base = 0.6)
        chromaLumaIsFlat(image, BandingAxis.HORIZONTAL, period)

        val before = amplitudeAt(
            profile(image.planes[0], image.width, image.height, BandingAxis.HORIZONTAL).let { r ->
                val g = profile(image.planes[1], image.width, image.height, BandingAxis.HORIZONTAL)
                DoubleArray(r.size) { r[it] - g[it] }
            },
            period,
        )

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue("colour banding should be repaired", outcome.applied)
        val estimate = outcome.estimate
        assertNotNull(estimate)
        assertEquals(BandingKind.CHROMATIC, estimate!!.kind)
        assertEquals(BandingAxis.HORIZONTAL, estimate.axis)
        assertEquals(period, estimate.periodPx, 20.0)

        val corrected = outcome.planes!!
        val after = amplitudeAt(
            profile(corrected[0], image.width, image.height, BandingAxis.HORIZONTAL).let { r ->
                val g = profile(corrected[1], image.width, image.height, BandingAxis.HORIZONTAL)
                DoubleArray(r.size) { r[it] - g[it] }
            },
            period,
        )
        assertTrue("chroma residual $after should be far below $before", after < before * 0.25)
    }

    @Test
    fun repairsShorterColorBanding() {
        val period = 180.0
        val image = build(320, 900, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.08, -0.12), base = 0.55)

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue(outcome.applied)
        assertEquals(BandingKind.CHROMATIC, outcome.estimate!!.kind)
        assertEquals(period, outcome.estimate!!.periodPx, 15.0)
    }

    @Test
    fun repairsVerticalColorBanding() {
        val period = 300.0
        val image = build(1200, 400, BandingAxis.VERTICAL, period, flatLumaDepths(-0.10, -0.15), base = 0.6)

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue(outcome.applied)
        assertEquals(BandingAxis.VERTICAL, outcome.estimate!!.axis)
        assertEquals(BandingKind.CHROMATIC, outcome.estimate!!.kind)
        assertEquals(period, outcome.estimate!!.periodPx, 20.0)
    }

    @Test
    fun stillHandlesLuminanceBanding() {
        val period = 10.0
        val depth = 0.15
        val image = build(
            320, 480, BandingAxis.HORIZONTAL, period,
            doubleArrayOf(depth, depth, depth), base = 0.5,
        )

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue(outcome.applied)
        assertEquals(BandingKind.LUMINANCE, outcome.estimate!!.kind)
        assertEquals(period, outcome.estimate!!.periodPx, 1.0)
    }

    @Test
    fun rejectsFlatImage() {
        val width = 300
        val height = 600
        val planes = Array(3) { FloatArray(width * height) { 0.5f } }

        val outcome = ChromaBandingRepair.repair(planes, width, height)

        assertFalse("a flat image must not be touched", outcome.applied)
    }

    @Test
    fun rejectsWarmLightGradient() {
        // Illumination that dims smoothly and slightly warmer toward the bottom: no periodicity.
        val width = 300
        val height = 900
        val planes = Array(3) { FloatArray(width * height) }
        for (y in 0 until height) {
            val t = y.toDouble() / (height - 1)
            val scale = 0.85 - 0.25 * t
            for (x in 0 until width) {
                planes[0][y * width + x] = (0.55 * scale * 1.04).toFloat()
                planes[1][y * width + x] = (0.55 * scale).toFloat()
                planes[2][y * width + x] = (0.55 * scale * 0.96).toFloat()
            }
        }

        val outcome = ChromaBandingRepair.repair(planes, width, height)

        assertFalse("a smooth warm gradient must not be treated as colour banding", outcome.applied)
    }

    @Test
    fun zeroStrengthIsNoOp() {
        val period = 200.0
        val image = build(300, 800, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15))

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height, strength = 0f)

        assertFalse(outcome.applied)
    }

    @Test
    fun doesNotMutateInputPlanes() {
        val period = 250.0
        val image = build(300, 800, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15))
        val snapshots = image.planes.map { it.copyOf() }

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue(outcome.applied)
        for (c in 0 until 3) {
            assertArrayEquals("plane $c must be untouched", snapshots[c], image.planes[c], 0f)
            assertFalse("plane $c must be a new array", outcome.planes!![c] === image.planes[c])
        }
    }

    @Test
    fun toleratesSensorNoise() {
        val period = 320.0
        val image = build(
            400, 1000, BandingAxis.HORIZONTAL, period,
            flatLumaDepths(-0.10, -0.15), base = 0.6, noise = 0.02,
        )

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        assertTrue("noise must not hide the banding", outcome.applied)
        assertEquals(BandingKind.CHROMATIC, outcome.estimate!!.kind)
        assertEquals(period, outcome.estimate!!.periodPx, 25.0)
    }

    @Test
    fun repairsWithPeriodSuppliedByDetector() {
        // The shape of the real pipeline: A reports 391 rows, B only fits.
        val period = 391.0
        val image = build(400, 1200, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15), base = 0.6)
        val before = amplitudeAt(
            profile(image.planes[0], image.width, image.height, BandingAxis.HORIZONTAL).let { r ->
                val g = profile(image.planes[1], image.width, image.height, BandingAxis.HORIZONTAL)
                DoubleArray(r.size) { r[it] - g[it] }
            },
            period,
        )

        val outcome = ChromaBandingRepair.repairWithPeriod(
            image.planes, image.width, image.height, period, axis = BandingAxis.HORIZONTAL,
        )

        assertTrue(outcome.applied)
        assertEquals(BandingKind.CHROMATIC, outcome.estimate!!.kind)
        // The supplied period is refined locally by up to 6% before fitting.
        assertEquals(period, outcome.estimate!!.periodPx, period * 0.06)

        val after = amplitudeAt(
            profile(outcome.planes!![0], image.width, image.height, BandingAxis.HORIZONTAL).let { r ->
                val g = profile(outcome.planes!![1], image.width, image.height, BandingAxis.HORIZONTAL)
                DoubleArray(r.size) { r[it] - g[it] }
            },
            period,
        )
        assertTrue("residual $after should be far below $before", after < before * 0.3)
    }

    @Test
    fun doesNotTurnSceneStructureIntoNewStripes() {
        // A slide with text lines about 127 px apart. At a 391 px band period that spacing is
        // the third harmonic, and fitting harmonics re-applied the text rows as a ~6% new
        // fine stripe pattern across the whole photo.
        val width = 300
        val height = 1200
        val period = 391.0
        val planes = Array(3) { FloatArray(width * height) }
        for (y in 0 until height) {
            val textRow = y % 127 < 9
            for (x in 0 until width) {
                val base = if (textRow) 0.35 else 0.82
                planes[0][y * width + x] = base.toFloat()
                planes[1][y * width + x] = base.toFloat()
                planes[2][y * width + x] = base.toFloat()
            }
        }
        // project chromatic banding on top
        val depths = flatLumaDepths(-0.10, -0.15)
        for (y in 0 until height) {
            val wave = sin(2.0 * PI * y / period)
            for (x in 0 until width) {
                val index = y * width + x
                for (c in 0 until 3) {
                    planes[c][index] = (planes[c][index] * (1.0 + depths[c] * wave)).toFloat().coerceIn(0f, 1f)
                }
            }
        }
        val before = profile(planes[0], width, height, BandingAxis.HORIZONTAL)

        val outcome = ChromaBandingRepair.repairWithPeriod(planes, width, height, period)
        assertTrue(outcome.applied)

        val after = profile(outcome.planes!![0], width, height, BandingAxis.HORIZONTAL)
        val gain = DoubleArray(height) { if (before[it] > 1e-6) after[it] / before[it] else 1.0 }
        val fineRipple = amplitudeAt(gain, 127.0)
        val bandCorrection = amplitudeAt(gain, period)

        assertTrue("new 127 px stripes must stay negligible, was $fineRipple", fineRipple < 0.02)
        assertTrue("the 391 px correction should still be applied, was $bandCorrection", bandCorrection > 0.05)
    }

    @Test
    fun refinesAPeriodThatIsSlightlyOff() {
        val width = 300
        val height = 1200
        val truePeriod = 391.0
        val image = build(width, height, BandingAxis.HORIZONTAL, truePeriod, flatLumaDepths(-0.10, -0.15), base = 0.6)
        val before = amplitudeAt(chromaOf(image.planes, width, height), truePeriod)

        // Detector reports 370: 5% low.
        val outcome = ChromaBandingRepair.repairWithPeriod(image.planes, width, height, 370.0)
        assertTrue(outcome.applied)

        val after = amplitudeAt(chromaOf(outcome.planes!!, width, height), truePeriod)
        assertTrue("a 5% period error must still be corrected, residual $after vs $before", after < before * 0.25)
    }

    private fun chromaOf(planes: Array<FloatArray>, width: Int, height: Int): DoubleArray {
        val r = profile(planes[0], width, height, BandingAxis.HORIZONTAL)
        val g = profile(planes[1], width, height, BandingAxis.HORIZONTAL)
        return DoubleArray(r.size) { r[it] - g[it] }
    }

    @Test
    fun suppliedPeriodDoesNothingWithoutModulation() {
        // A detector false positive must not damage the preview.
        val width = 300
        val height = 900
        val planes = Array(3) { FloatArray(width * height) { 0.5f } }

        val outcome = ChromaBandingRepair.repairWithPeriod(planes, width, height, 391.0)

        assertFalse(outcome.applied)
    }

    @Test
    fun rejectsUnresolvableSuppliedPeriod() {
        val width = 300
        val height = 600
        val period = 500.0 // fewer than two cycles in 600 rows
        val image = build(width, height, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15))

        val outcome = ChromaBandingRepair.repairWithPeriod(image.planes, width, height, period)

        assertFalse("a period longer than half the axis has no usable cycles", outcome.applied)
    }

    @Test
    fun suppliedPeriodRespectsStrength() {
        val period = 300.0
        val image = build(300, 900, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.10, -0.15))

        val full = ChromaBandingRepair.repairWithPeriod(image.planes, 300, 900, period, strength = 1f)
        val half = ChromaBandingRepair.repairWithPeriod(image.planes, 300, 900, period, strength = 0.5f)

        assertTrue(full.applied)
        assertTrue(half.applied)
        val fullShifts = image.planes[0].indices.count { abs(image.planes[0][it] - full.planes!![0][it]) > 0.01f }
        val halfShifts = image.planes[0].indices.count { abs(image.planes[0][it] - half.planes!![0][it]) > 0.01f }
        assertTrue("half strength must change fewer pixels", halfShifts < fullShifts)
    }

    @Test
    fun falsePositivesStayBoundedOnColourfulScenes() {
        // Deliberately adversarial: smooth random colour fields with no periodic structure.
        // Long-period chroma banding and slow scene colour are intrinsically close, so a
        // small share is unavoidable; the bound guards against the gates opening up again.
        val width = 320
        val height = 700
        var flagged = 0
        repeat(30) { index ->
            val random = Random(5000L + index)
            val gridW = 12
            val gridH = 12
            val gridR = DoubleArray(gridW * gridH) { 0.35 + random.nextDouble() * 0.35 }
            val gridG = DoubleArray(gridW * gridH) { 0.35 + random.nextDouble() * 0.35 }
            val gridB = DoubleArray(gridW * gridH) { 0.35 + random.nextDouble() * 0.35 }
            val planes = Array(3) { FloatArray(width * height) }

            fun sample(grid: DoubleArray, x: Int, y: Int): Double {
                val gy = y.toDouble() / (height - 1) * (gridH - 1)
                val gx = x.toDouble() / (width - 1) * (gridW - 1)
                val y0 = gy.toInt().coerceAtMost(gridH - 2)
                val fy = gy - y0
                val x0 = gx.toInt().coerceAtMost(gridW - 2)
                val fx = gx - x0
                val top = grid[y0 * gridW + x0] * (1 - fx) + grid[y0 * gridW + x0 + 1] * fx
                val bottom = grid[(y0 + 1) * gridW + x0] * (1 - fx) + grid[(y0 + 1) * gridW + x0 + 1] * fx
                return top * (1 - fy) + bottom * fy
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index2 = y * width + x
                    planes[0][index2] = sample(gridR, x, y).toFloat().coerceIn(0f, 1f)
                    planes[1][index2] = sample(gridG, x, y).toFloat().coerceIn(0f, 1f)
                    planes[2][index2] = sample(gridB, x, y).toFloat().coerceIn(0f, 1f)
                }
            }
            if (ChromaBandingRepair.repair(planes, width, height).applied) flagged++
        }

        assertTrue("colourful scenes wrongly repaired: $flagged/30", flagged <= 6)
    }

    @Test
    fun keepsOutputInRange() {
        val period = 200.0
        val image = build(300, 700, BandingAxis.HORIZONTAL, period, flatLumaDepths(-0.30, -0.40), base = 0.9)

        val outcome = ChromaBandingRepair.repair(image.planes, image.width, image.height)

        outcome.planes!!.forEach { plane ->
            assertTrue(plane.all { it.isFinite() && it >= 0f && it <= 1f })
        }
    }
}
