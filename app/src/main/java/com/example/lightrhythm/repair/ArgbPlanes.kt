package com.example.lightrhythm.repair

import kotlin.math.roundToInt

/**
 * Converts between packed ARGB pixels and the R/G/B planes used by [ChromaBandingRepair].
 *
 * Pure Kotlin on purpose: the conversion is the easy part to get subtly wrong (alpha loss,
 * channel clamping), so it is unit-tested on the JVM instead of only on a device.
 */
internal object ArgbPlanes {

    /** Three planes of 0..1 samples, in R, G, B order. */
    fun toPlanes(pixels: IntArray): Array<FloatArray> {
        val planes = Array(3) { FloatArray(pixels.size) }
        for (i in pixels.indices) {
            val color = pixels[i]
            planes[0][i] = ((color ushr 16) and 0xFF) / 255f
            planes[1][i] = ((color ushr 8) and 0xFF) / 255f
            planes[2][i] = (color and 0xFF) / 255f
        }
        return planes
    }

    /**
     * Packs corrected planes back into ARGB, keeping the alpha of [originalPixels].
     *
     * @return a new array; neither input is modified.
     */
    fun fromPlanes(planes: Array<FloatArray>, originalPixels: IntArray): IntArray {
        require(planes.size == 3) { "expected R, G and B planes" }
        val count = originalPixels.size
        planes.forEach { require(it.size >= count) { "plane smaller than the pixel buffer" } }

        val output = IntArray(count)
        for (i in 0 until count) {
            val alpha = (originalPixels[i] ushr 24) and 0xFF
            val red = clampChannel(planes[0][i])
            val green = clampChannel(planes[1][i])
            val blue = clampChannel(planes[2][i])
            output[i] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return output
    }

    private fun clampChannel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
}
