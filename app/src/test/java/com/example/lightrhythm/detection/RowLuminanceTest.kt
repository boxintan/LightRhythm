package com.example.lightrhythm.detection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RowLuminanceTest {
    @Test
    fun uniformGrayHasNoVariationAcrossRows() {
        val result = computeRowLuminance(7, 5) { _, row -> row.fill(0xff808080.toInt()) }
        assertArrayEquals(DoubleArray(5) { 128.0 }, result, 1e-9)
    }

    @Test
    fun channelWeightsDistinguishRedGreenAndBlue() {
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt())
        val result = computeRowLuminance(2, 3) { y, row -> row.fill(colors[y]) }
        assertArrayEquals(doubleArrayOf(76.245, 149.685, 29.07), result, 1e-9)
    }

    @Test
    fun averagesAllColumnsAndPreservesTopToBottomRowCoordinates() {
        val rows = arrayOf(
            intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()),
            intArrayOf(0xff404040.toInt(), 0xff808080.toInt()),
            intArrayOf(0xffffffff.toInt(), 0xffffffff.toInt()),
        )
        val result = computeRowLuminance(2, rows.size) { y, row -> rows[y].copyInto(row) }
        assertArrayEquals(doubleArrayOf(127.5, 96.0, 255.0), result, 1e-9)
        assertArrayEquals(intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()), rows[0])
    }

    @Test
    fun singlePixelImageHasOneOutput() {
        val result = computeRowLuminance(1, 1) { _, row -> row[0] = 0xff252525.toInt() }
        assertEquals(1, result.size)
        assertEquals(37.0, result[0], 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyImage() {
        computeRowLuminance(0, 1) { _, _ -> error("不应读取无效图片") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTransparencyInsteadOfTreatingHiddenRgbAsVisibleContent() {
        computeRowLuminance(1, 1) { _, row -> row[0] = 0x00ffffff }
    }
}
