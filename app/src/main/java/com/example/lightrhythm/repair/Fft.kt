package com.example.lightrhythm.repair

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 Cooley-Tukey FFT.
 *
 * Kept private to the repair package so the algorithm stays dependency-free;
 * only power-of-two sizes are accepted, which is enough for banding analysis.
 */
internal object Fft {

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(im.size == n) { "Real and imaginary arrays must have the same size" }
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }
        if (n == 1) return

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val swapRe = re[i]
                re[i] = re[j]
                re[j] = swapRe
                val swapIm = im[i]
                im[i] = im[j]
                im[j] = swapIm
            }
        }

        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val stepRe = cos(angle)
            val stepIm = sin(angle)
            var start = 0
            while (start < n) {
                var twiddleRe = 1.0
                var twiddleIm = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half
                    val oddRe = re[odd] * twiddleRe - im[odd] * twiddleIm
                    val oddIm = re[odd] * twiddleIm + im[odd] * twiddleRe
                    val evenRe = re[even]
                    val evenIm = im[even]
                    re[even] = evenRe + oddRe
                    im[even] = evenIm + oddIm
                    re[odd] = evenRe - oddRe
                    im[odd] = evenIm - oddIm
                    val nextRe = twiddleRe * stepRe - twiddleIm * stepIm
                    twiddleIm = twiddleRe * stepIm + twiddleIm * stepRe
                    twiddleRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
