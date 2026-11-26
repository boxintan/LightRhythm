package com.example.lightrhythm.detection

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*
import kotlin.random.Random

class PeriodSearchTest {
    private fun peaks(values: DoubleArray,threshold: Double): List<Int> =
        (4 until values.size-1).filter { p ->
            values[p]>=threshold && values[p]>=values[p-1] && values[p]>values[p+1] && values[p/2]<=-.15
        }

    @Test fun screenedSearchKeepsExactPeaksAtThresholdBoundariesAndAcrossFftSizes() {
        val random=Random(9527)
        for(size in listOf(64,128,256,512,960,1600,4096)) {
            val maxLag=minOf(1024,size/2)
            val cases=listOf(
                DoubleArray(size),
                DoubleArray(size) { 128+1e-7*sin(2*PI*it/16) },
                DoubleArray(size) { if(it<size/2) 80.0 else 80+16*sin(2*PI*it/17) },
            )+List(4) { phase -> DoubleArray(size) { i ->
                18*sin(2*PI*i/(15.5+phase)+.3*phase)+random.nextDouble(-20.0,20.0)
            } }
            for(signal in cases) {
                val reference=DoubleArray(maxLag+2) { correlation(signal,signal,it) }
                val near=reference[16].coerceIn(.5,.999999)
                for(threshold in listOf(.65,near,(near-1e-10).coerceAtLeast(.5),(near+1e-10).coerceAtMost(1.0))) {
                    val fast=periodSearchCorrelations(signal,maxLag,4,threshold)
                    val expected=peaks(reference,threshold)
                    assertEquals("size=$size threshold=$threshold",expected,peaks(fast,threshold))
                    for(p in expected) for(lag in listOf(p,p-1,p+1,p/2,2,3))
                        assertEquals("Reported decisions must use original Pearson values",reference[lag],fast[lag],0.0)
                }
            }
        }
    }
}
