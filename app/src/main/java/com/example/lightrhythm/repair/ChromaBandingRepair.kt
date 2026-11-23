package com.example.lightrhythm.repair

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which image component carried the banding that was found. */
internal enum class BandingKind { LUMINANCE, CHROMATIC }

internal data class ChannelRepairOutcome(
    /** Corrected R, G, B planes, or null when nothing was applied. */
    val planes: Array<FloatArray>?,
    val estimate: BandingEstimate?,
    val applied: Boolean,
)

/**
 * Repairs banding that lives in the colour channels rather than in brightness.
 *
 * A DLP/LED projector photographed with a rolling shutter can modulate the three channels
 * by different amounts. The result is visible as green/purple bands while overall brightness
 * stays almost flat, so a luminance-only repair finds nothing to do.
 *
 * Two different signals are analysed per axis:
 *  - the luminance profile, for ordinary brightness flicker (short periods, conservative gate)
 *  - the chroma profiles log(R/G) and log(B/G), for colour banding (long periods allowed,
 *    because natural illumination varies almost achromatically and cannot fake this)
 *
 * Whichever candidate wins, every channel is refitted at the chosen period and divided out
 * in log space. The caller's arrays are never mutated.
 */
internal object ChromaBandingRepair {

    private const val MIN_PERIOD_PX = 8.0

    /** Projector colour banding is wide; a 1200 px preview showed a 391 px period. */
    private const val MAX_PERIOD_PX = 512.0

    /** Peak-to-median required in the chroma spectrum. Chroma noise is low, so this is strict. */
    private const val MIN_CHROMA_RATIO = 8.0

    /**
     * Minimum chroma modulation in log space. 8-bit quantization plus JPEG noise gives
     * roughly 1.5/255 ~= 0.006, so anything below this is not distinguishable from noise.
     */
    private const val MIN_CHROMA_AMPLITUDE = 0.006

    /** Luminance path keeps the conservative gates from [BandingRepair]. */
    private const val MIN_LUMA_RATIO = 15.0
    private const val MAX_LUMA_PERIOD_PX = 64.0
    private const val MIN_LUMA_EXPLAINED = 0.7

    /**
     * Share of the chroma residual energy the fitted sinusoids must explain.
     * Measured: pure colour banding 0.93, a real projector capture 0.81, random smooth
     * colour scenes 0.58 on average (0.89 worst case). The gate sits below the real
     * capture and above the bulk of the scene distribution.
     */
    private const val MIN_CHROMA_EXPLAINED = 0.75

    /**
     * Flicker modulates the whole frame, so the same colour wave must appear in every
     * vertical (or horizontal) strip of the image. Scene colour does not: two slices of a
     * random textured photo share almost nothing. Without this gate a smooth multi-coloured
     * scene was flagged 100% of the time at long periods.
     */
    private const val STRIPS = 3
    private const val MIN_STRIP_AMPLITUDE_RATIO = 0.5
    private const val MIN_STRIP_PHASE_COHERENCE = 0.75

    private const val MAX_HARMONICS = 3
    private const val GAIN_MIN = 0.5
    private const val GAIN_MAX = 2.0
    private const val EPS = 1e-6

    private const val PLANES = 3

    /**
     * @param planes R, G, B samples in 0..1, row-major, each at least width * height long.
     * @param strength 0 leaves the image untouched, 1 applies the full correction.
     */
    fun repair(
        planes: Array<FloatArray>,
        width: Int,
        height: Int,
        strength: Float = 1f,
        axisHint: BandingAxis? = null,
    ): ChannelRepairOutcome {
        require(planes.size == PLANES) { "expected R, G and B planes" }
        require(width > 0 && height > 0) { "width and height must be positive" }
        val needed = width * height
        for (plane in planes) {
            require(plane.size >= needed) { "plane has ${plane.size} values but $needed are required" }
        }

        val blend = strength.coerceIn(0f, 1f)
        if (blend == 0f) return ChannelRepairOutcome(null, null, applied = false)

        val candidates = ArrayList<AxisAnalysis>(2)
        if (axisHint != BandingAxis.VERTICAL) {
            analyze(planes, width, height, BandingAxis.HORIZONTAL)?.let(candidates::add)
        }
        if (axisHint != BandingAxis.HORIZONTAL) {
            analyze(planes, width, height, BandingAxis.VERTICAL)?.let(candidates::add)
        }
        val best = candidates.maxByOrNull { it.score }
            ?: return ChannelRepairOutcome(null, null, applied = false)

        val corrected = Array(PLANES) { channel ->
            applyGain(planes[channel], width, height, best.gains[channel], best.axis, blend)
        }
        val estimate = BandingEstimate(
            axis = best.axis,
            periodPx = best.periodPx,
            amplitude = best.amplitude,
            confidence = best.confidence,
            kind = best.kind,
        )
        return ChannelRepairOutcome(corrected, estimate, applied = true)
    }

    private class AxisAnalysis(
        val axis: BandingAxis,
        val periodPx: Double,
        val amplitude: Double,
        val confidence: Double,
        val kind: BandingKind,
        val gains: Array<DoubleArray>,
        val score: Double,
    )

    private class Fit(
        val frequencies: DoubleArray,
        val cosines: DoubleArray,
        val sines: DoubleArray,
        val amplitude: Double,
        val explainedFraction: Double,
    )

    private fun analyze(
        planes: Array<FloatArray>,
        width: Int,
        height: Int,
        axis: BandingAxis,
    ): AxisAnalysis? {
        val n = if (axis == BandingAxis.HORIZONTAL) height else width
        if (n < 32) return null

        val profiles = Array(PLANES) { channel ->
            if (axis == BandingAxis.HORIZONTAL) rowProfile(planes[channel], width, height)
            else columnProfile(planes[channel], width, height)
        }

        val logProfiles = Array(PLANES) { channel ->
            val mean = profiles[channel].average()
            if (mean <= EPS) return null
            val floorValue = max(mean * 0.05, EPS)
            DoubleArray(n) { ln(max(profiles[channel][it], floorValue)) }
        }

        val lumaResidual = detrend(DoubleArray(n) { i ->
            (logProfiles[0][i] + logProfiles[1][i] + logProfiles[2][i]) / PLANES
        })
        // Chroma drifts slowly and hardly at all, so a linear detrend is used here: a quadratic
        // fit would absorb a meaningful share of a 391 px period spread over ~1200 px.
        val chromaRedGreen = detrendLinear(DoubleArray(n) { i -> logProfiles[0][i] - logProfiles[1][i] })
        val chromaBlueGreen = detrendLinear(DoubleArray(n) { i -> logProfiles[2][i] - logProfiles[1][i] })

        val kMin = ceil(nextPowerOfTwo(max(n, 8)) / MAX_PERIOD_PX).toInt().coerceAtLeast(1)
        val kMaxLimit = floor(nextPowerOfTwo(max(n, 8)) / MIN_PERIOD_PX).toInt()

        val lumaPeak = findPeak(lumaResidual, kMin, kMaxLimit) ?: return null

        val rgPeak = findPeak(chromaRedGreen, kMin, kMaxLimit)
        val bgPeak = findPeak(chromaBlueGreen, kMin, kMaxLimit)
        val useRedGreen = (rgPeak?.ratio ?: 0.0) >= (bgPeak?.ratio ?: 0.0)
        val chromaPeak = if (useRedGreen) rgPeak else bgPeak
        val chromaResidual = if (useRedGreen) chromaRedGreen else chromaBlueGreen
        val chromaSignalIndex = if (useRedGreen) 0 else 1

        // Which candidate explains what we see?
        var kind: BandingKind? = null
        var period = 0.0
        var ratio = 0.0
        var amplitude = 0.0
        var confidence = 0.0

        if (chromaPeak != null) {
            val chromaFit = fitHarmonics(chromaResidual, chromaPeak.frequency)
            val chromaAmplitude = chromaFit.amplitude
            if (chromaPeak.ratio >= MIN_CHROMA_RATIO &&
                chromaAmplitude >= MIN_CHROMA_AMPLITUDE &&
                chromaFit.explainedFraction >= MIN_CHROMA_EXPLAINED &&
                coherentAcrossStrips(planes, width, height, axis, chromaPeak.frequency, chromaSignalIndex)
            ) {
                kind = BandingKind.CHROMATIC
                period = 1.0 / chromaPeak.frequency
                ratio = chromaPeak.ratio
                amplitude = chromaAmplitude
                confidence = (chromaPeak.ratio / (chromaPeak.ratio + MIN_CHROMA_RATIO)).coerceIn(0.0, 1.0)
            }
        }

        if (kind == null && lumaPeak.period <= MAX_LUMA_PERIOD_PX && lumaPeak.ratio >= MIN_LUMA_RATIO) {
            val lumaFit = fitHarmonics(lumaResidual, lumaPeak.frequency)
            if (lumaFit.explainedFraction >= MIN_LUMA_EXPLAINED && lumaFit.amplitude >= MIN_CHROMA_AMPLITUDE) {
                kind = BandingKind.LUMINANCE
                period = lumaPeak.period
                ratio = lumaPeak.ratio
                amplitude = lumaFit.amplitude
                confidence = (lumaPeak.ratio / (lumaPeak.ratio + MIN_LUMA_RATIO)).coerceIn(0.0, 1.0)
            }
        }

        val resolvedKind = kind ?: return null
        if (period !in MIN_PERIOD_PX..MAX_PERIOD_PX) return null

        val fundamental = 1.0 / period
        val gains = Array(PLANES) { channel ->
            gainCurve(logProfiles[channel], profiles[channel], fundamental)
        }
        return AxisAnalysis(axis, period, amplitude, confidence, resolvedKind, gains, ratio)
    }

    private class Peak(val frequency: Double, val period: Double, val ratio: Double)

    /** Narrowband coefficient of the chroma signal inside one strip. */
    private class Coeff(val amplitude: Double, val phase: Double)

    /**
     * True when the colour wave has the same phase and comparable depth in every strip of
     * the frame. Flicker does; scene colour does not.
     */
    private fun coherentAcrossStrips(
        planes: Array<FloatArray>,
        width: Int,
        height: Int,
        axis: BandingAxis,
        frequency: Double,
        signalIndex: Int,
    ): Boolean {
        val horizontal = axis == BandingAxis.HORIZONTAL
        val extent = if (horizontal) width else height
        if (extent < STRIPS * 16) return false
        val stripSize = extent / STRIPS

        val coefficients = ArrayList<Coeff>(STRIPS)
        for (s in 0 until STRIPS) {
            val from = s * stripSize
            val to = if (s == STRIPS - 1) extent else from + stripSize
            val coefficient = stripCoefficient(planes, width, height, axis, from, to, frequency, signalIndex)
                ?: return false
            coefficients.add(coefficient)
        }

        val amplitudes = coefficients.map { it.amplitude }
        val maxAmplitude = amplitudes.max()
        if (maxAmplitude <= EPS) return false
        if (amplitudes.min() / maxAmplitude < MIN_STRIP_AMPLITUDE_RATIO) return false

        var sumCos = 0.0
        var sumSin = 0.0
        for (coefficient in coefficients) {
            sumCos += cos(coefficient.phase)
            sumSin += sin(coefficient.phase)
        }
        val phaseCoherence = sqrt(sumCos * sumCos + sumSin * sumSin) / STRIPS
        return phaseCoherence >= MIN_STRIP_PHASE_COHERENCE
    }

    private fun stripCoefficient(
        planes: Array<FloatArray>,
        width: Int,
        height: Int,
        axis: BandingAxis,
        from: Int,
        to: Int,
        frequency: Double,
        signalIndex: Int,
    ): Coeff? {
        val horizontal = axis == BandingAxis.HORIZONTAL
        val n = if (horizontal) height else width
        val profiles = Array(PLANES) { channel ->
            if (horizontal) rowProfileRange(planes[channel], width, height, from, to)
            else columnProfileRange(planes[channel], width, height, from, to)
        }
        val means = profiles.map { it.average() }
        if (means.any { it <= EPS }) return null

        val logs = Array(PLANES) { channel ->
            val floorValue = max(means[channel] * 0.05, EPS)
            DoubleArray(n) { ln(max(profiles[channel][it], floorValue)) }
        }
        val signal = DoubleArray(n) {
            if (signalIndex == 0) logs[0][it] - logs[1][it] else logs[2][it] - logs[1][it]
        }
        val residual = detrendLinear(signal)

        var real = 0.0
        var imaginary = 0.0
        for (i in 0 until n) {
            val angle = 2.0 * PI * frequency * i
            real += residual[i] * cos(angle)
            imaginary += residual[i] * sin(angle)
        }
        val a = 2.0 * real / n
        val b = 2.0 * imaginary / n
        return Coeff(sqrt(a * a + b * b), atan2(b, a))
    }

    private fun rowProfileRange(plane: FloatArray, width: Int, height: Int, x0: Int, x1: Int): DoubleArray {
        val count = (x1 - x0).coerceAtLeast(1)
        val profile = DoubleArray(height)
        for (y in 0 until height) {
            var sum = 0.0
            val base = y * width
            for (x in x0 until x1) sum += plane[base + x]
            profile[y] = sum / count
        }
        return profile
    }

    private fun columnProfileRange(plane: FloatArray, width: Int, height: Int, y0: Int, y1: Int): DoubleArray {
        val count = (y1 - y0).coerceAtLeast(1)
        val profile = DoubleArray(width)
        for (y in y0 until y1) {
            val base = y * width
            for (x in 0 until width) profile[x] += plane[base + x]
        }
        for (x in 0 until width) profile[x] /= count
        return profile
    }

    private fun findPeak(signal: DoubleArray, kMin: Int, kMaxLimit: Int): Peak? {
        val spectrum = powerSpectrum(signal)
        val half = spectrum.size / 2
        val kMax = kMaxLimit.coerceAtMost(half - 1)
        if (kMax - kMin < 2) return null

        val power = DoubleArray(kMax - kMin + 1) { spectrum.power[kMin + it] }
        var bestIndex = 0
        for (i in power.indices) if (power[i] > power[bestIndex]) bestIndex = i
        val median = power.copyOf().also { it.sort() }[power.size / 2]
        val ratio = power[bestIndex] / max(median, EPS)

        val refined = refinePeak(power, bestIndex)
        val bin = kMin + refined
        if (bin <= 0.0) return null
        val frequency = bin / spectrum.size
        if (frequency <= 0.0 || frequency >= 0.5) return null
        return Peak(frequency, 1.0 / frequency, ratio)
    }

    /**
     * Fits the fundamental and up to two harmonics to a log profile and returns the
     * multiplier that removes them.
     */
    private fun gainCurve(logProfile: DoubleArray, profile: DoubleArray, fundamental: Double): DoubleArray {
        val n = logProfile.size
        val residual = detrend(logProfile)
        val fit = fitHarmonics(residual, fundamental)
        if (fit.frequencies.isEmpty()) return DoubleArray(n) { 1.0 }

        val floorValue = max(profile.average() * 0.05, EPS)
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

    private fun fitHarmonics(signal: DoubleArray, fundamental: Double): Fit {
        val n = signal.size
        var energy = 0.0
        for (value in signal) energy += value * value
        if (energy < EPS) return Fit(DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0, 0.0)

        val maxHarmonic = min(MAX_HARMONICS, floor(0.5 / fundamental).toInt().coerceAtLeast(1))
        val frequencies = ArrayList<Double>(maxHarmonic)
        val cosines = ArrayList<Double>(maxHarmonic)
        val sines = ArrayList<Double>(maxHarmonic)
        var amplitudeSquared = 0.0
        var explained = 0.0

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
                explained += harmonicEnergy
            }
        }
        return Fit(
            frequencies.toDoubleArray(),
            cosines.toDoubleArray(),
            sines.toDoubleArray(),
            sqrt(amplitudeSquared),
            (explained / energy).coerceIn(0.0, 1.0),
        )
    }

    /** Least-squares linear detrend, used for chroma where only slow drift is natural. */
    private fun detrendLinear(signal: DoubleArray): DoubleArray {
        val n = signal.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            sumX += x
            sumY += signal[i]
            sumXX += x * x
            sumXY += x * signal[i]
        }
        val denominator = n * sumXX - sumX * sumX
        val slope = if (abs(denominator) < 1e-12) 0.0 else (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n
        return DoubleArray(n) { i -> signal[i] - (intercept + slope * i) }
    }

    /** Least-squares quadratic detrend: illumination is smooth, banding is periodic. */
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

    private class Spectrum(val power: DoubleArray, val size: Int)

    private fun powerSpectrum(signal: DoubleArray): Spectrum {
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
        for (k in 1 until half) power[k] = re[k] * re[k] + im[k] * im[k]
        return Spectrum(power, size)
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
        plane: FloatArray,
        width: Int,
        height: Int,
        gain: DoubleArray,
        axis: BandingAxis,
        blend: Float,
    ): FloatArray {
        val output = plane.copyOf()
        if (axis == BandingAxis.HORIZONTAL) {
            for (y in 0 until height) {
                val factor = (1.0 + blend * (gain[y] - 1.0)).toFloat()
                if (factor == 1f) continue
                val base = y * width
                for (x in 0 until width) {
                    val corrected = output[base + x] * factor
                    output[base + x] = if (corrected < 0f) 0f else if (corrected > 1f) 1f else corrected
                }
            }
        } else {
            for (x in 0 until width) {
                val factor = (1.0 + blend * (gain[x] - 1.0)).toFloat()
                if (factor == 1f) continue
                for (y in 0 until height) {
                    val index = y * width + x
                    val corrected = output[index] * factor
                    output[index] = if (corrected < 0f) 0f else if (corrected > 1f) 1f else corrected
                }
            }
        }
        return output
    }

    private fun rowProfile(plane: FloatArray, width: Int, height: Int): DoubleArray {
        val profile = DoubleArray(height)
        for (y in 0 until height) {
            var sum = 0.0
            val base = y * width
            for (x in 0 until width) sum += plane[base + x]
            profile[y] = sum / width
        }
        return profile
    }

    private fun columnProfile(plane: FloatArray, width: Int, height: Int): DoubleArray {
        val profile = DoubleArray(width)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) profile[x] += plane[base + x]
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
