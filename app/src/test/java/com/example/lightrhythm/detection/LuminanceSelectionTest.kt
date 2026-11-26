package com.example.lightrhythm.detection

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class LuminanceSelectionTest {
    @Test fun selectionMatchesSortedOrderForEveryRankAcrossTextureDistributions() {
        val random=Random(2947)
        for(size in listOf(1,2,3,16,32,64)) {
            val cases=listOf(
                DoubleArray(size) { 130.0 },
                DoubleArray(size) { it.toDouble() },
                DoubleArray(size) { (size-it).toDouble() },
                DoubleArray(size) { if(it%2==0) 0.0 else 255.0 },
            )+List(32) { DoubleArray(size) { random.nextDouble(0.0,255.0) } }+
                List(32) { DoubleArray(size) { random.nextInt(0,8).toDouble() } }
            for(values in cases) {
                val sorted=values.sortedArray()
                for(rank in values.indices)
                    assertEquals(sorted[rank],selectLuminanceOrderStatistic(values.copyOf(),rank),0.0)
            }
        }
    }
}
