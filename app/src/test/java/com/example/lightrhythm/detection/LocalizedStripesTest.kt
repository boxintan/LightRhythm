package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.StripeAxis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class LocalizedStripesTest {
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or
        (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)

    private fun analyze(config: DetectionConfig = DetectionConfig(), pixel: (Int,Int)->Int) =
        analyzeLocalizedStripes(512,512,config) { x,y,w,h,out ->
            assertTrue(x>=0 && y>=0 && x+w<=512 && y+h<=512)
            for(yy in 0 until h) for(xx in 0 until w) out[yy*w+xx]=pixel(x+xx,y+yy)
        }

    private fun screen(x: Int,y: Int,vertical: Boolean=false): Int {
        if(x !in 80 until 400 || y !in 128 until 320) {
            val gray=if((x+y/4)%20<9) 90 else 170
            return rgb(gray,gray,gray)
        }
        if((x-80)%70 in 10..14 && (y-128)%45 in 8..28) return rgb(245,245,245)
        val wave=(28*sin(2*PI*(if(vertical) x else y)/16)).roundToInt()
        return rgb(20,85+wave,180+wave)
    }

    @Test fun smallColoredScreenSurvivesNeutralTextAndPhysicalSurroundings() {
        val regions=analyze { x,y -> screen(x,y) }
        val horizontal=regions.filter { it.axis==StripeAxis.HORIZONTAL }
        assertTrue(horizontal.isNotEmpty())
        assertTrue(horizontal.all { abs(it.periodPixels-16)<=1 })
        assertTrue(horizontal.all { it.x in 64..112 && it.y in 112..160 && it.y+it.height<=336 })
    }

    @Test fun rotatedScreenReportsColumnsRatherThanRows() {
        val regions=analyze { x,y -> screen(x,y,true) }
        assertTrue(regions.any { it.axis==StripeAxis.VERTICAL && abs(it.periodPixels-16)<=1 })
    }

    @Test fun graySlatsAndTheirReflectionDoNotProposeColoredRegions() {
        assertTrue(analyze { x,y ->
            val reflectedY=if(y<256) y else 511-y
            val gray=if((x+reflectedY/3)%24<10) 70 else 205
            rgb(gray,gray,gray)
        }.isEmpty())
    }

    @Test fun singleStripCannotAccumulateVotesThroughSeveralColorChannels() {
        assertTrue(analyze { x,y ->
            if(x !in 80 until 400 || y !in 128 until 320) rgb(150,150,150)
            else {
                val wave=if(x<150) (30*sin(2*PI*y/16)).toInt() else 0
                rgb(20,85+wave,180+wave)
            }
        }.isEmpty())
    }

    @Test fun strongerAmplitudeRequirementIsRespected() {
        assertTrue(analyze(DetectionConfig(minRmsAmplitude=100.0)) { x,y -> screen(x,y) }.isEmpty())
    }

    @Test fun largerLocalScaleCoversFourCyclesOfLongerBands() {
        val evidence=analyzeLocalBandingTiles(768,768,DetectionConfig()) { x,y,w,h,out ->
            for(yy in 0 until h) for(xx in 0 until w) {
                val gray=(170+24*sin(2*PI*((y+yy)-.1*(x+xx))/48)).roundToInt()
                out[yy*w+xx]=rgb(gray,gray,gray)
            }
        }
        assertNotNull(evidence)
        assertTrue(evidence!!.tiles.all { it.size==256 && abs(it.periodRows-48)<=1 })
    }

    @Test fun coloredGradientNoiseAndTransparencyDoNotCreatePeriodicity() {
        assertTrue(analyze { x,y -> rgb(30,60+y/8,180+x/10) }.isEmpty())
        val random=Random(483)
        assertTrue(analyze { _,_ -> rgb(20,85+random.nextInt(-30,31),185) }.isEmpty())
        assertTrue(analyze { x,y -> screen(x,y) and 0xffffff }.isEmpty())
    }
}
