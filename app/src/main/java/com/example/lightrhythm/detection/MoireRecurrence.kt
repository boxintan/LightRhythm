package com.example.lightrhythm.detection

import kotlin.math.*

/** Fully finite windows only. Squared-energy tables cover every sampling-grid phase.
 * Dot products retain source iteration order; near a decision boundary callers use exact().
 */
internal class MoireRecurrence(private val values: DoubleArray,private val size: Int,private val step: Int) {
    private data class Energy(val width: Int,val sums: DoubleArray)
    private val energies=arrayOfNulls<Energy>(step*step)
    private val total=values.sumOf { it*it }

    private fun energy(firstX: Int,firstY: Int,columns: Int,rows: Int): Double {
        val phaseX=firstX%step; val phaseY=firstY%step
        val phase=phaseY*step+phaseX
        val table=energies[phase] ?: run {
            val width=(size-phaseX+step-1)/step
            val height=(size-phaseY+step-1)/step
            val stride=width+1
            val sums=DoubleArray(stride*(height+1))
            for(y in 0 until height) {
                var row=0.0
                for(x in 0 until width) {
                    val value=values[(phaseY+y*step)*size+phaseX+x*step]
                    row+=value*value
                    sums[(y+1)*stride+x+1]=sums[y*stride+x+1]+row
                }
            }
            Energy(stride,sums).also { energies[phase]=it }
        }
        val x=firstX/step; val y=firstY/step
        val right=x+columns; val bottom=y+rows; val stride=table.width; val sums=table.sums
        return sums[bottom*stride+right]-sums[y*stride+right]-sums[bottom*stride+x]+sums[y*stride+x]
    }

    fun estimate(dx: Int,dy: Int,lag: Int): Double = evaluate(dx,dy,lag,false)
    fun exact(dx: Int,dy: Int,lag: Int): Double = evaluate(dx,dy,lag,true)

    private fun evaluate(dx: Int,dy: Int,lag: Int,exact: Boolean): Double {
        val shiftX=dx*lag; val shiftY=dy*lag
        val startX=((maxOf(0,-shiftX)+step-1)/step)*step
        val startY=((maxOf(0,-shiftY)+step-1)/step)*step
        val endX=minOf(size,size-shiftX); val endY=minOf(size,size-shiftY)
        val columns=maxOf(0,(endX-startX+step-1)/step)
        val rows=maxOf(0,(endY-startY+step-1)/step)
        if(columns*rows<maxOf(80,size*size/120)) return 0.0
        val displacement=shiftY*size+shiftX
        var ab=0.0; var aa=0.0; var bb=0.0
        if(exact) {
            for(y in startY until endY step step) for(x in startX until endX step step) {
                val index=y*size+x
                val a=values[index]; val b=values[index+displacement]
                ab+=a*b; aa+=a*a; bb+=b*b
            }
        } else {
            aa=energy(startX,startY,columns,rows)
            bb=energy(startX+shiftX,startY+shiftY,columns,rows)
            if(aa<=maxOf(total,1.0)*1e-4 || bb<=maxOf(total,1.0)*1e-4) return evaluate(dx,dy,lag,true)
            for(y in startY until endY step step) for(x in startX until endX step step) {
                val index=y*size+x
                ab+=values[index]*values[index+displacement]
            }
        }
        return if(aa*bb>1e-9) ab/sqrt(aa*bb) else 0.0
    }
}
