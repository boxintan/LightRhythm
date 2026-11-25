package com.example.lightrhythm.model

/** Input-pixel coordinates of the central window; sampling also uses sourceMarginRows above/below. */
data class LocalStripeTile(val x: Int, val y: Int, val size: Int, val periodRows: Int,
    val slope: Double, val correlation: Double, val column: Int, val row: Int,
    val sourceMarginRows: Int = 16)

/** Local stripe evidence only; testedCells counts spatial positions at the selected scale.
 * Multiple tested scales never contribute additional votes. No hardware cause or repair template.
 */
data class LocalBandingEvidence(val tiles: List<LocalStripeTile>, val testedCells: Int = 24)
