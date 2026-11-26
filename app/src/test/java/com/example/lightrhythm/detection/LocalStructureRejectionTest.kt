package com.example.lightrhythm.detection

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class LocalStructureRejectionTest {
    private fun analyze(width: Int = 640, height: Int = 480, pixel: (Int, Int)->Int) =
        analyzeLocalBanding(width,height) { y,row ->
            for(x in row.indices) {
                val gray=pixel(x,y).coerceIn(0,255)
                row[x]=(255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

    @Test fun convergingSlatsDoNotAssembleUnrelatedRowsIntoBandEvidence() {
        for (width in listOf(480,640)) {
            val evidence=analyze(width,480) { x,y ->
                val phase=(y-.18*x)/(12-.006*x)
                val fraction=phase-floor(phase)
                if(fraction < .18) 90 else (210-35*fraction).toInt()
            }
            assertNull("Perspective slats at width $width",evidence)
        }
    }

    @Test fun smoothConvergingLinesAreAlsoRejectedWithoutDependingOnSharpEdges() {
        assertNull(analyze { x,y ->
            (180+20*sin(2*PI*(y-.18*x)/(12-.006*x))).toInt()
        })
    }

    @Test fun parallelBandsRetainTheirPeriodAndTiltAcrossRows() {
        for (slope in listOf(-.1,0.0,.1)) {
            val evidence=analyze { x,y -> (180+20*sin(2*PI*(y-slope*x)/12)).toInt() }
            assertNotNull("Missing parallel bands with slope $slope",evidence)
            assertTrue(evidence!!.tiles.all { it.periodRows in 11..13 })
        }
    }
}
