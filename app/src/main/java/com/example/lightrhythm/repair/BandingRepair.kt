package com.example.lightrhythm.repair

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Direction in which the flicker banding repeats across the image. */
internal enum class BandingAxis { HORIZONTAL, VERTICAL }

/**
 * Banding parameters measured in image space.
 *
 * @param periodPx distance between two consecutive bright (or dark) bands.
 * @param amplitude relative modulation depth in log space (0.1 means roughly +/-10% brightness).
 * @param confidence spectral peak-to-median ratio; higher means more reliable detection.
 */
internal data class BandingEstimate(
    val axis: BandingAxis,
    val periodPx: Double,
    val amplitude: Double,
    val confidence: Double,
)

internal data class RepairOutcome(
    val luma: FloatArray,
    val estimate: BandingEstimate?,
    val applied: Boolean,
)

/**
 * Removes periodic brightness banding produced by PWM-driven LEDs under a rolling shutter.
 *
 * Model: observed(x, y) = scene(x, y) * flicker(axis coordinate).
 * Taking logarithms turns the multiplicative flicker into an additive one, so we estimate the
 * flicker curve from the mean-luminance profile (quadratic trend removal + harmonic fit) and
 * then divide it back out. Working in log space keeps the correction correct even when scene
 * brightness varies along the banding axis.
 *
 * Input is normalized luminance (0..1 recommended; any positive scale works). The caller's
 * array is never mutated and the returned array is always a fresh copy.
 */
internal object BandingRepair {

    /** Below this period the "bands" are indistinguishable from texture. */
    private const val MIN_PERIOD_PX = 2.0

    /** Above this period the component looks like lighting, not flicker. */
    private const val MAX_PERIOD_PX = 128.0

    /** Peak-to-median spectral power required before we touch the image. */
    private const val MIN_CONFIDENCE = 15.0

    /** Minimum relative modulation we consider worth correcting. */
    private const val MIN_AMPLITUDE = 0.004

    /**
     * Share of the residual energy the fitted harmonics must explain.
     * A sharp edge is broadband and stops here; a flicker pattern is periodic and passes.
     */
    private const val MIN_EXPLAINED_FRACTION = 0.3

    private const val MAX_HARMONICS = 3
    private const val GAIN_MIN = 0.5
    private const val GAIN_MAX = 2.0
    private const val EPS = 1e-6

    /**
     * Estimates and removes banding.
     *
     * @param luma row-major luminance values, at least [width] * [height] entries.
     * @param strength 0 keeps the image untouched, 1 applies the full correction.
     * @param axisHint restricts the search to one axis when the caller already knows it.
     * @param supplied parameters from an external detector; skips estimation when given.
     */
    fun repair(
        luma: FloatArray,
        width: Int,
        height: Int,
        strength: Float = 1f,
        axisHint: BandingAxis? = null,
        supplied: BandingEstimate? = null,
    ): RepairOutcome {
        require(width > 0 && height > 0) { "width and height must be positive" }
        val needed = width * height
        require(luma.size >= needed) { "luma has ${luma.size} values but $needed are required" }

        val blend = strength.coerceIn(0f, 1f)
        val rows = rowProfile(luma, width, height)
        val columns = columnProfile(luma, width, height)

        val estimate: BandingEstimate?
        val gain: DoubleArray
        if (supplied != null) {
            estimate = supplied
            val profile = if (supplied.axis == BandingAxis.HORIZONTAL) rows else columns
            gain = buildGain(profile, supplied.periodPx)
        } else {
            val candidates = ArrayList<Analysis>(2)
            if (axisHint != BandingAxis.VERTICAL) {
                analyzeProfile(rows, BandingAxis.HORIZONTAL)?.let(candidates::add)
            }
            if (axisHint != BandingAxis.HORIZONTAL) {
                analyzeProfile(columns, BandingAxis.VERTICAL)?.let(candidates::add)
            }
            val best = candidates
                .filter { it.estimate.confidence >= MIN_CONFIDENCE && it.estimate.amplitude >= MIN_AMPLITUDE }
                .maxByOrNull { it.estimate.confidence }
                ?: return RepairOutcome(luma.copyOf(), null, applied = false)
            estimate = best.estimate
            gain = best.gain
        }

        if (blend == 0f) return RepairOutcome(luma.copyOf(), estimate, applied = false)
        return RepairOutcome(applyGain(luma, width, height, gain, estimate.axis, blend), estimate, applied = true)
    }

    private class Analysis(val gain: DoubleArray, val estimate: BandingEstimate)

    private class HarmonicFit(
        val frequencies: DoubleArray,
        val cosines: DoubleArray,
        val sines: DoubleArray,
        val amplitude: Double,
        val explainedFraction: Double,
    )

    private class PowerSpectrum(val power: DoubleArray, val size: Int)

    private fun analyzeProfile(profile: DoubleArray, axis: BandingAxis): Analysis? {
        val n = profile.size
        if (n < 16) return null
        val mean = profile.average()
        if (mean <= EPS) return null

        val residual = detrend(logProfile(profile, mean))
        val spectrum = powerSpectrum(residual)
        val half = spectrum.size / 2
        val kMin = ceil(spectrum.size / MAX_PERIOD_PX).toInt().coerceAtLeast(1)
        val kMax = floor(spectrum.size / MIN_PERIOD_PX).toInt().coerceAtMost(half - 1)
        if (kMax - kMin < 2) return null

        val power = DoubleArray(kMax - kMin + 1) { spectrum.power[kMin + it] }
        var bestIndex = 0
        for (i in power.indices) {
            if (power[i] > power[bestIndex]) bestIndex = i
        }
        val median = power.copyOf().also { it.sort() }[power.size / 2]
        val confidence = power[bestIndex] / max(median, EPS)

        val refinedIndex = refinePeak(power, bestIndex)
        val frequency = (kMin + refinedIndex) / spectrum.size
        if (frequency <= 0.0 || frequency >= 0.5) return null
        val period = 1.0 / frequency
        if (period < MIN_PERIOD_PX || period > MAX_PERIOD_PX) return null

        val fit = fitHarmonics(residual, frequency)
        if (fit.explainedFraction < MIN_EXPLAINED_FRACTION) return null
        val estimate = BandingEstimate(axis, period, fit.amplitude, confidence)
        return Analysis(gainCurve(profile, mean, fit), estimate)
    }

    /** Least-squares quadratic detrend: scene lighting is smooth, flicker is fast. */
    private fun detrend(signal: DoubleArray): DoubleArray {
        val n = signal.size
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var t0 = 0.0
        var t1 = 0.0
        var t2 = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val x2 = x * x
            s0 += 1.0
            s1 += x
            s2 += x2
            s3 += x2 * x
            s4 += x2 * x2
            t0 += signal[i]
            t1 += x * signal[i]
            t2 += x2 * signal[i]
        }

        val determinant = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2)
        if (abs(determinant) < 1e-12) {
            val average = t0 / n
            return DoubleArray(n) { signal[it] - average }
        }

        val detA = t0 * (s2 * s4 - s3 * s3) - s1 * (t1 * s4 - s3 * t2) + s2 * (t1 * s3 - s2 * t2)
        val detB = s0 * (t1 * s4 - s3 * t2) - t0 * (s1 * s4 - s3 * s2) + s2 * (s1 * t2 - t1 * s2)
        val detC = s0 * (s2 * t2 - t1 * s3) - s1 * (s1 * t2 - t1 * s2) + t0 * (s1 * s3 - s2 * s2)
        val a = detA / determinant
        val b = detB / determinant
        val c = detC / determinant

        return DoubleArray(n) { i ->
            val x = i.toDouble()
            signal[i] - (a + b * x + c * x * x)
        }
    }

    private fun fitHarmonics(signal: DoubleArray, fundamental: Double): HarmonicFit {
        val n = signal.size
        var energy = 0.0
        for (value in signal) energy += value * value
        if (energy < EPS) {
            return HarmonicFit(DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0, 0.0)
        }

        val maxHarmonic = min(MAX_HARMONICS, floor(0.5 / fundamental).toInt().coerceAtLeast(1))
        val frequencies = ArrayList<Double>(maxHarmonic)
        val cosines = ArrayList<Double>(maxHarmonic)
        val sines = ArrayList<Double>(maxHarmonic)
        var amplitudeSquared = 0.0
        var explainedEnergy = 0.0

        for (harmonic in 1..maxHarmonic) {
            val frequency = fundamental * harmonic
            if (frequency >= 0.5) break
            var projectionCos = 0.0
            var projectionSin = 0.0
            for (i in 0 until n) {
                val angle = 2.0 * PI * frequency * i
                projectionCos += signal[i] * cos(angle)
                projectionSin += signal[i] * sin(angle)
            }
            val a = 2.0 * projectionCos / n
            val b = 2.0 * projectionSin / n
            val harmonicEnergy = n * (a * a + b * b) / 2.0
            if (harmonic == 1 || harmonicEnergy > energy * 0.02) {
                frequencies.add(frequency)
                cosines.add(a)
                sines.add(b)
                amplitudeSquared += a * a + b * b
                explainedEnergy += harmonicEnergy
            }
        }
        return HarmonicFit(
            frequencies.toDoubleArray(),
            cosines.toDoubleArray(),
            sines.toDoubleArray(),
            sqrt(amplitudeSquared),
            (explainedEnergy / energy).coerceIn(0.0, 1.0),
        )
    }

    private fun gainCurve(profile: DoubleArray, mean: Double, fit: HarmonicFit): DoubleArray {
        val n = profile.size
        val floorValue = max(mean * 0.05, EPS)
        val gain = DoubleArray(n)
        for (i in 0 until n) {
            if (profile[i] < floorValue) {
                gain[i] = 1.0
                continue
            }
            var banding = 0.0
            for (h in fit.frequencies.indices) {
                val angle = 2.0 * PI * fit.frequencies[h] * i
                banding += fit.cosines[h] * cos(angle) + fit.sines[h] * sin(angle)
            }
            gain[i] = exp(-banding).coerceIn(GAIN_MIN, GAIN_MAX)
        }
        return gain
    }

    private fun buildGain(profile: DoubleArray, periodPx: Double): DoubleArray {
        if (profile.size < 4) return DoubleArray(profile.size) { 1.0 }
        val mean = profile.average()
        if (mean <= EPS) return DoubleArray(profile.size) { 1.0 }
        val period = periodPx.coerceIn(MIN_PERIOD_PX, MAX_PERIOD_PX)
        val residual = detrend(logProfile(profile, mean))
        val fit = fitHarmonics(residual, 1.0 / period)
        return gainCurve(profile, mean, fit)
    }

    private fun logProfile(profile: DoubleArray, mean: Double): DoubleArray {
        val floorValue = max(mean * 0.05, EPS)
        return DoubleArray(profile.size) { ln(max(profile[it], floorValue)) }
    }

    private fun powerSpectrum(signal: DoubleArray): PowerSpectrum {
        val n = signal.size
        val size = nextPowerOfTwo(max(n, 8))
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val denominator = (n - 1).coerceAtLeast(1)
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / denominator)
            re[i] = signal[i] * window
        }
        Fft.transform(re, im)
        val half = size / 2
        val power = DoubleArray(half)
        for (k in 1 until half) {
            power[k] = re[k] * re[k] + im[k] * im[k]
        }
        return PowerSpectrum(power, size)
    }

    private fun refinePeak(power: DoubleArray, index: Int): Double {
        if (index <= 0 || index >= power.lastIndex) return index.toDouble()
        val left = ln(power[index - 1].coerceAtLeast(EPS))
        val center = ln(power[index].coerceAtLeast(EPS))
        val right = ln(power[index + 1].coerceAtLeast(EPS))
        val denominator = left - 2.0 * center + right
        return if (abs(denominator) < 1e-9) index.toDouble() else index + 0.5 * (left - right) / denominator
    }

    private fun applyGain(
        luma: FloatArray,
        width: Int,
        height: Int,
        gain: DoubleArray,
        axis: BandingAxis,
        blend: Float,
    ): FloatArray {
        val output = luma.copyOf()
        if (axis == BandingAxis.HORIZONTAL) {
            for (y in 0 until height) {
                val factor = (1.0 + blend * (gain[y] - 1.0)).toFloat()
                if (factor == 1f) continue
                val base = y * width
                for (x in 0 until width) {
                    val corrected = output[base + x] * factor
                    output[base + x] = if (corrected < 0f) 0f else corrected
                }
            }
        } else {
            for (x in 0 until width) {
                val factor = (1.0 + blend * (gain[x] - 1.0)).toFloat()
                if (factor == 1f) continue
                for (y in 0 until height) {
                    val index = y * width + x
                    val corrected = output[index] * factor
                    output[index] = if (corrected < 0f) 0f else corrected
                }
            }
        }
        return output
    }

    private fun rowProfile(luma: FloatArray, width: Int, height: Int): DoubleArray {
        val profile = DoubleArray(height)
        for (y in 0 until height) {
            var sum = 0.0
            val base = y * width
            for (x in 0 until width) sum += luma[base + x]
            profile[y] = sum / width
        }
        return profile
    }

    private fun columnProfile(luma: FloatArray, width: Int, height: Int): DoubleArray {
        val profile = DoubleArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) profile[x] += luma[base + x]
        }
        for (x in 0 until width) profile[x] /= height
        return profile
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }
}
