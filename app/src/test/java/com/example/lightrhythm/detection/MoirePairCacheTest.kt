package com.example.lightrhythm.detection

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class MoirePairCacheTest {
    @Test fun partialMasksKeepExactCorrelationsAndDoNotLeakBetweenTilesOrChannels() {
        val random=Random(1842)
        val directions=listOf(0 to 1,1 to 0,1 to 1,-1 to 1,1 to 2,-1 to 2,2 to 1,-2 to 1)
        for(size in listOf(16,32,64,128)) {
            val step=when(size) { 16,32 -> 1; 64 -> 2; else -> 3 }
            val cache=MoirePairCache(size,step,directions)
            for(mask in listOf(BooleanArray(size*size) { random.nextDouble()<.8 },
                BooleanArray(size*size) { it%size>size/4 },BooleanArray(size*size) { true })) {
                cache.reset(mask)
                repeat(2) {
                    val signal=DoubleArray(size*size) { i -> if(mask[i]) random.nextDouble(-20.0,20.0) else Double.NaN }
                    for((direction,pair) in directions.withIndex()) {
                        val (dx,dy)=pair
                        for(lag in 0..size/3/maxOf(abs(dx),abs(dy))+1) {
                            var ab=0.0; var aa=0.0; var bb=0.0; var count=0
                            for(y in 0 until size step step) for(x in 0 until size step step) {
                                val xx=x+dx*lag; val yy=y+dy*lag
                                if(xx !in 0 until size || yy !in 0 until size) continue
                                val a=signal[y*size+x]; val b=signal[yy*size+xx]
                                if(!a.isFinite() || !b.isFinite()) continue
                                ab+=a*b; aa+=a*a; bb+=b*b; count++
                            }
                            val expected=if(count>=maxOf(80,size*size/120) && aa*bb>1e-9) ab/sqrt(aa*bb) else 0.0
                            assertEquals(expected,cache.correlation(direction,lag,signal),0.0)
                        }
                    }
                }
            }
        }
    }
}
