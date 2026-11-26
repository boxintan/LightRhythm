package com.example.lightrhythm.detection

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class MoireRecurrenceTest {
    @Test fun energyTablesRespectEveryLagDirectionAndSubsamplePhase() {
        val random=Random(351)
        for(size in listOf(16,32,64,128)) {
            val step=when(size) { 16,32 -> 1; 64 -> 2; else -> 3 }
            for(signal in listOf(DoubleArray(size*size),DoubleArray(size*size) { 5.0 },
                DoubleArray(size*size) { if(it<4) 255.0 else random.nextDouble(-1e-4,1e-4) },
                DoubleArray(size*size) { random.nextDouble(-15.0,15.0) },
                DoubleArray(size*size) { 20*sin(2*PI*(it/size)/15)+5*cos(it%size/8.0) })) {
                val fast=MoireRecurrence(signal,size,step)
                for((dx,dy) in listOf(0 to 1,1 to 0,1 to 1,-1 to 1,1 to 2,-1 to 2,2 to 1,-2 to 1)) {
                    for(lag in 0..(size/3/maxOf(abs(dx),abs(dy))+1)) {
                        var aa=0.0; var bb=0.0; var ab=0.0; var count=0
                        for(y in 0 until size step step) for(x in 0 until size step step) {
                            val xx=x+dx*lag; val yy=y+dy*lag
                            if(xx !in 0 until size || yy !in 0 until size) continue
                            val a=signal[y*size+x]; val b=signal[yy*size+xx]
                            ab+=a*b; aa+=a*a; bb+=b*b; count++
                        }
                        val expected=if(count>=maxOf(80,size*size/120) && aa*bb>1e-9) ab/sqrt(aa*bb) else 0.0
                        assertEquals(expected,fast.exact(dx,dy,lag),0.0)
                        assertEquals(expected,fast.estimate(dx,dy,lag),1e-9)
                    }
                }
            }
        }
    }
}
