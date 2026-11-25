package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LocalBandingScaleTest {
    private val width = 480
    private val height = 280
    private fun detect(config: DetectionConfig = DetectionConfig(), pixel: (Int, Int) -> Int) =
        analyzeLocalBanding(width, height, config) { y, row ->
            for (x in row.indices) {
                val gray = pixel(x, y).coerceIn(0, 255)
                row[x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

    @Test fun smallTiltedScreenRetainsEvidenceWithoutItsDarkSurroundings() {
        val evidence = detect { x, y ->
            if (x in 100..390 && y in 80..205)
                (180 + 20 * sin(2 * PI * (y - .1 * x) / 6)).toInt() else 30
        }
        assertNotNull(evidence)
        assertTrue("$evidence", evidence!!.tiles.all { it.size == 64 && it.periodRows == 6 })
        assertEquals(evidence.tiles.size, evidence.tiles.map { it.column to it.row }.distinct().size)
        assertTrue(evidence.tiles.size <= evidence.testedCells)
        assertTrue(evidence.tiles.all {
            it.sourceMarginRows == 8 && it.y - it.sourceMarginRows >= 0 &&
                it.y + it.size + it.sourceMarginRows <= height && it.x + it.size <= width
        })
    }

    @Test fun smallFallbackStillHonorsAmplitudeAndPeriodConfiguration() {
        fun pixel(x: Int, y: Int) = if (x in 100..390 && y in 65..250)
            (180 + 10 * sin(2 * PI * (y - .1 * x) / 6)).toInt() else 30
        assertNull(detect(DetectionConfig(minRmsAmplitude = 30.0), ::pixel))
        assertNull(detect(DetectionConfig(minPeriodRows = 20), ::pixel))
    }

    @Test fun smallRepeatedGlyphsAndSingleTexturePatchDoNotTriggerFallback() {
        assertNull(detect { x, y -> if (x % 32 < 6 && y % 16 < 9) 40 else 220 })
        assertNull(detect { x, y ->
            if (x in 195..255 && y in 105..165) (180 + 20*sin(2*PI*y/6)).toInt() else 180
        })
    }

    @Test fun existingCoarseEvidenceIsKeptWithoutPoolingScaleVotes() {
        val evidence = detect { x, y -> (180 + 20*sin(2*PI*(y-.1*x)/12)).toInt() }
        assertNotNull(evidence)
        assertTrue(evidence!!.tiles.all { it.size == 128 && it.sourceMarginRows == 16 })
        assertEquals(evidence.tiles.size, evidence.tiles.map { it.column to it.row }.distinct().size)
    }
}
