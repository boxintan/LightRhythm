package com.example.lightrhythm.detection

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class MoireDetectorTest {
    private fun image(width: Int = 512, height: Int = 512, pixel: (Int, Int) -> Int): MoireResult =
        analyzeMoire(width, height) { y, row -> for (x in row.indices) row[x] = pixel(x,y) }
    private fun rgb(r: Int, g: Int, b: Int) = (255 shl 24) or (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)

    @Test fun curvedChromaTextureProducesSpatialCandidates() {
        val result = image { x,y -> val wave = (18*sin(2*PI*(y/15.0+x*x/18000.0))).toInt(); rgb(185+wave,185,185-wave) }
        assertTrue(result.isSuspected)
        assertTrue(result.tiles.size >= 3)
        assertTrue(result.tiles.all { it.x >= 0 && it.y >= 0 && it.x+it.size <= 512 && it.y+it.size <= 512 })
    }
    @Test fun uniformTintAndLinearShadingAreRejected() {
        assertFalse(image { x,y -> rgb(140+x/8,150+y/8,170) }.isSuspected)
    }
    @Test fun broadColorWaveIsRejected() {
        assertFalse(image { x,_ -> val v=(20*sin(2*PI*x/512)).toInt(); rgb(185+v,185,185-v) }.isSuspected)
    }
    @Test fun straightColorTextureRemainsAnAmbiguousCandidate() {
        assertTrue(image { _,y -> val v=(20*sin(2*PI*y/16)).toInt(); rgb(185+v,185,185-v) }.isSuspected)
    }
    @Test fun grayscaleStripesAndTextAreRejected() {
        assertFalse(image { x,y -> val v=if (y%18<4 || (x%80<5 && y%70<40)) 70 else 200; rgb(v,v,v) }.isSuspected)
    }
    @Test fun noiseDoesNotSupplyStableRecurrence() {
        val random = Random(347)
        assertFalse(image { _,_ -> rgb(180+random.nextInt(-20,21),180,180+random.nextInt(-20,21)) }.isSuspected)
    }
    @Test fun oneLocalPatternIsInsufficient() {
        assertFalse(image { x,y -> val v=if (x in 40..140 && y in 40..140) (20*sin(2*PI*y/12)).toInt() else 0; rgb(180+v,180,180-v) }.isSuspected)
    }
    @Test fun transparencyAndDarkPixelsAreRejected() {
        assertFalse(image { _,y -> rgb(30+(15*sin(2*PI*y/12)).toInt(),30,30) }.isSuspected)
        assertFalse(image { _,y -> rgb(180+(15*sin(2*PI*y/12)).toInt(),180,180) and 0xffffff }.isSuspected)
    }
    @Test fun smallImageIsExplicitlyUnassessed() {
        val result = image(128,128) { _,_ -> rgb(180,180,180) }
        assertEquals(0,result.testedTiles)
        assertFalse(result.isSuspected)
    }

    @Test fun affineTintedNeutralStripesDoNotBecomeColorFringesThroughNormalization() {
        for(period in listOf(9,17,29)) for(slope in listOf(-.15,0.0,.1)) {
            val result=image(320,384) { x,y ->
                val light=140+60*sin(2*PI*(y-slope*x)/period)
                rgb((light+42).roundToInt(),(light+14).roundToInt(),light.roundToInt())
            }
            assertFalse("neutral texture: period=$period slope=$slope",result.isSuspected)
        }
    }

    @Test fun independentColorFringesSurviveAnUnrelatedBrightnessPatternAndTint() {
        val result=image { x,y ->
            val brightness=18*sin(2*PI*y/47)
            val fringe=20*sin(2*PI*(y/15.0+x*x/18000.0))
            rgb((165+brightness+fringe+12).roundToInt(),(165+brightness).roundToInt(),
                (165+brightness-fringe+5).roundToInt())
        }
        assertTrue(result.isSuspected)
    }

    @Test fun spatialVotesMustComeFromThreeNonOverlappingSourceAreas() {
        fun tile(x: Int,y: Int,size: Int)=MoireTile(x,y,size,"R-G",0,1,12.0,.9)
        assertFalse(MoireResult(listOf(tile(0,0,128),tile(8,8,128),tile(16,16,128)),16).isSuspected)
        assertTrue(MoireResult(listOf(tile(0,0,64),tile(64,0,64),tile(0,64,64)),16).isSuspected)
        // A large overlapping candidate must not hide a valid separate small-tile triple.
        assertTrue(MoireResult(listOf(tile(0,0,128),tile(0,0,32),tile(32,0,32),tile(64,0,32)),16).isSuspected)
    }

    @Test fun colorChannelPermutationDoesNotDetermineWhetherFringesAreDetected() {
        val permutations=listOf(listOf(0,1,2),listOf(0,2,1),listOf(1,0,2),listOf(1,2,0),listOf(2,0,1),listOf(2,1,0))
        for(order in permutations) {
            val result=image(320,384) { x,y ->
                val wave=(20*sin(2*PI*(y/15.0+x*x/18000.0)+.7)).roundToInt()
                val channels=listOf(170+wave,175,180-wave)
                rgb(channels[order[0]],channels[order[1]],channels[order[2]])
            }
            assertTrue("RGB permutation $order",result.isSuspected)
        }
    }
}
