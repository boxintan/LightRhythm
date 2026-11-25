package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.LocalBandingEvidence
import com.example.lightrhythm.model.BandingStatus
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LocalBandingTest {
    private fun analyze(config: DetectionConfig = DetectionConfig(), pixel: (Int, Int) -> Int): LocalBandingEvidence? =
        analyzeLocalBanding(640, 480, config) { y, row ->
            for (x in row.indices) {
                val gray = pixel(x,y).coerceIn(0,255)
                row[x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

    @Test fun slopedBandsSurviveWholeImagePhaseCancellation() {
        val evidence = analyze { x,y -> (180 + 14*sin(2*PI*(y-0.1*x)/14)).toInt() }
        assertNotNull(evidence)
        assertTrue(evidence!!.tiles.all { it.periodRows in 13..15 })
    }
    @Test fun imageEntryReportsLocalEvidenceWithoutFabricatingRepairOffsets() {
        val result = analyzeImageBanding(640,480) { y,row ->
            for (x in row.indices) {
                val gray = (180+14*sin(2*PI*(y-0.1*x)/14)).toInt()
                row[x] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
        assertEquals(BandingStatus.SUSPECTED_LOCAL_BANDING,result.status)
        assertTrue(result.isSuspected)
        assertNotNull(result.localBandingEvidence)
        assertTrue(result.candidateRowOffsets.isEmpty())
    }
    @Test fun sparseRepeatedGlyphsDoNotBecomeBackgroundBands() {
        assertNull(analyze { x,y -> if (x%32 < 6 && y%16 < 9) 40 else 220 })
    }
    @Test fun linearShadingAndUniformTintAreNotPeriodic() {
        assertNull(analyze { x,y -> 140+x/16+y/16 })
    }
    @Test fun isolatedSmallPatchIsNotMultiRegionEvidence() {
        assertNull(analyze { x,y -> if (x in 220..310 && y in 160..250) (180+15*sin(2*PI*y/12)).toInt() else 180 })
    }
    @Test fun configuredAmplitudeAndMinimumPeriodRemainEnforced() {
        assertNull(analyze(DetectionConfig(minRmsAmplitude = 20.0)) { _,y -> (180+8*sin(2*PI*y/12)).toInt() })
        assertNull(analyze(DetectionConfig(minPeriodRows = 40)) { _,y -> (180+14*sin(2*PI*y/12)).toInt() })
    }
}
