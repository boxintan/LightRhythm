package com.example.lightrhythm.model

enum class StripeAxis { HORIZONTAL, VERTICAL }

/** A measured rectangle in input pixels, not a semantic screen/object classification.
 * periodPixels uses rows for HORIZONTAL and columns for VERTICAL; never a whole-image repair map.
 * correlation is the weakest supporting strip, not a calibrated probability.
 */
data class LocalizedStripeRegion(val x: Int, val y: Int, val width: Int, val height: Int,
    val axis: StripeAxis, val periodPixels: Int, val channel: String, val correlation: Double)
