package com.example.lightrhythm.detection

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MoireScaleRegressionTest {
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun analyze(pixel: (Int, Int) -> Int): MoireResult = analyzeMoire(512, 512) { y, row ->
        for (x in row.indices) row[x] = pixel(x, y)
    }

    @Test fun tightlyCurvedColorFringesRemainVisible() {
        val result = analyze { x, y ->
            val radius = hypot(x - 256.0, y - 256.0)
            val wave = (12 * sin(2 * PI * radius / 7.0)).toInt()
            rgb(185 + wave, 185, 185 - wave)
        }
        assertTrue("Curved chroma must retain evidence in multiple spatial cells", result.isSuspected)
    }

    @Test fun randomChromaAcrossSeveralSeedsDoesNotBecomeRecurrence() {
        for (seed in listOf(0, 7, 48, 137, 902)) {
            val random = Random(seed)
            val result = analyze { _, _ ->
                rgb(180 + random.nextInt(-20, 21), 180, 180 + random.nextInt(-20, 21))
            }
            assertFalse("False positive for noise seed $seed", result.isSuspected)
        }
    }

    @Test fun largeColorPanelsDoNotBecomeFineFringes() {
        val result = analyze { x, y ->
            rgb(if (x < 256) 170 else 200, 185, if (y < 256) 175 else 205)
        }
        assertFalse(result.isSuspected)
    }
}
