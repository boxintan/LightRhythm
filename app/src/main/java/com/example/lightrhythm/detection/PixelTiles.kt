package com.example.lightrhythm.detection

/** Packed, row-major pixels in input coordinates; the caller owns the output buffer. */
internal typealias PixelTileReader = (left: Int, top: Int, width: Int, height: Int, pixels: IntArray) -> Unit

/** Compatibility adapter for synthetic images and callers that can only supply complete rows. */
internal fun rowTileReader(width: Int, readRow: (Int, IntArray) -> Unit): PixelTileReader {
    val row = IntArray(width)
    return { left, top, tileWidth, tileHeight, pixels ->
        for (y in 0 until tileHeight) {
            readRow(top + y, row)
            row.copyInto(pixels, y * tileWidth, left, left + tileWidth)
        }
    }
}
