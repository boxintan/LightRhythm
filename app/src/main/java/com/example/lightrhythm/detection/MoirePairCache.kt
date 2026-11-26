package com.example.lightrhythm.detection

import kotlin.math.*

/** A tile's valid-pixel pairs are identical in all six color representations. Filter once per
 * direction/lag and reuse their indices; the dot products keep the original summation order.
 */
internal class MoirePairCache(private val size: Int,private val step: Int,directions: List<Pair<Int,Int>>) {
    private class Plan(val startX: Int,val startY: Int,val endX: Int,val endY: Int,val delta: Int,
        val capacity: Int) {
        var indices: IntArray?=null
        var count = -1
    }
    private val minimumPairs=maxOf(80,size*size/120)
    private val plans=directions.map { (dx,dy) -> Array(size/3/maxOf(abs(dx),abs(dy))+2) { lag ->
        val sx=dx*lag; val sy=dy*lag
        val startX=(maxOf(0,-sx)+step-1)/step*step
        val startY=(maxOf(0,-sy)+step-1)/step*step
        val endX=minOf(size,size-sx); val endY=minOf(size,size-sy)
        Plan(startX,startY,endX,endY,sy*size+sx,
            maxOf(0,(endX-startX+step-1)/step)*maxOf(0,(endY-startY+step-1)/step))
    } }
    private lateinit var mask: BooleanArray

    fun reset(valid: BooleanArray) {
        mask=valid
        for(direction in plans) for(plan in direction) plan.count = -1
    }

    fun correlation(direction: Int,lag: Int,signal: DoubleArray): Double {
        val plan=plans[direction][lag]
        val indices=plan.indices ?: IntArray(plan.capacity).also { plan.indices=it }
        if(plan.count<0) {
            var count=0
            for(y in plan.startY until plan.endY step step) for(x in plan.startX until plan.endX step step) {
                val index=y*size+x
                if(mask[index] && mask[index+plan.delta]) indices[count++]=index
            }
            plan.count=count
        }
        if(plan.count<minimumPairs) return 0.0
        var ab=0.0; var aa=0.0; var bb=0.0
        for(i in 0 until plan.count) {
            val index=indices[i]
            val a=signal[index]; val b=signal[index+plan.delta]
            ab+=a*b; aa+=a*a; bb+=b*b
        }
        return if(aa*bb>1e-9) ab/sqrt(aa*bb) else 0.0
    }
}
