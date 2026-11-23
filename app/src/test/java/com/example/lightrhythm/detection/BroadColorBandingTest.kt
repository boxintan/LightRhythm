package com.example.lightrhythm.detection

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BroadColorBandingTest {
    private fun detect(color: (Int, Int) -> Int) = analyzeBroadColorBanding(256, 256) { y, row ->
        for (x in row.indices) row[x] = color(x, y)
    }
    private fun pixel(r: Int, g: Int, b: Int) = (255 shl 24) or (r shl 16) or (g shl 8) or b
    private fun broad(y: Int): Int {
        val wave = (30 * sin(PI * y / 255)).toInt()
        return pixel(170 - wave, 170, 170 + wave)
    }
    @Test fun singleBroadColorShiftDoesNotRequireTwoCycles() {
        assertNotNull(detect { _, y -> broad(y) })
    }
    @Test fun neutralLightingGradientIsNotColorBanding() {
        assertNull(detect { _, y -> val v = 100 + y / 3; pixel(v, v, v) })
    }
    @Test fun uniformColorCastIsNotHorizontalVariation() {
        assertNull(detect { _, _ -> pixel(140, 170, 190) })
    }
    @Test fun localColoredObjectLacksCrossRegionSupport() {
        assertNull(detect { x, y -> if (x < 70) broad(y) else pixel(170,170,170) })
    }
    @Test fun thinColoredLineIsNotBroadBanding() {
        assertNull(detect { _, y -> if (y in 125..127) pixel(130,170,210) else pixel(170,170,170) })
    }
    @Test fun insufficientVisibleBackgroundDoesNotTrigger() {
        assertNull(detect { _, _ -> pixel(10,10,10) })
    }
}
