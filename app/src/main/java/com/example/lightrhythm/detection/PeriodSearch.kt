package com.example.lightrhythm.detection

import kotlin.math.*

/** Fast screening only. Every possible peak, its neighbors/half-cycle and short aliases are
 * recomputed by the original Pearson routine before any decision or reported value is used.
 * The margin protects borderline comparisons from summation/FFT roundoff.
 */
internal fun periodSearchCorrelations(values: DoubleArray,maxLag: Int,minLag: Int,threshold: Double): DoubleArray {
    val margin=1e-6
    val size=values.size
    val sums=DoubleArray(size+1)
    val squares=DoubleArray(size+1)
    for(i in values.indices) {
        sums[i+1]=sums[i]+values[i]
        squares[i+1]=squares[i]+values[i]*values[i]
    }
    val dots=if(size>=512 && maxLag>=128) autocorrelationDots(values,maxLag+1) else null
    val estimated=DoubleArray(maxLag+2) { lag ->
        val count=size-lag
        val firstSum=sums[count]
        val secondSum=sums[size]-sums[lag]
        val aa=squares[count]-firstSum*firstSum/count
        val bb=squares[size]-squares[lag]-secondSum*secondSum/count
        val scale=maxOf(squares[count],squares[size]-squares[lag],1.0)
        if(aa<=scale*1e-5 || bb<=scale*1e-5) correlation(values,values,lag)
        else {
            var dot=if(dots==null) 0.0 else dots[lag]
            if(dots==null) for(i in 0 until count) dot+=values[i]*values[i+lag]
            ((dot-firstSum*secondSum/count)/sqrt(aa*bb)).coerceIn(-1.0,1.0)
        }
    }
    val possible=(minLag..maxLag).filter { lag ->
        estimated[lag]>=threshold-margin && estimated[lag]>=estimated[lag-1]-2*margin &&
            estimated[lag]>=estimated[lag+1]-2*margin && estimated[lag/2]<=-.15+margin
    }
    val exact=BooleanArray(estimated.size)
    fun refine(lag: Int) {
        if(!exact[lag]) { estimated[lag]=correlation(values,values,lag); exact[lag]=true }
    }
    if(possible.isNotEmpty()) {
        for(lag in 2 until minLag) refine(lag)
        for(lag in possible) { refine(lag); refine(lag-1); refine(lag+1); refine(lag/2) }
    }
    return estimated
}

/** Zero-padded radix-2 FFT: no circular wrap-around contributes to the lag products. */
private fun autocorrelationDots(values: DoubleArray,maxLag: Int): DoubleArray {
    var size=1
    while(size<values.size*2) size=size shl 1
    val real=DoubleArray(size)
    val imaginary=DoubleArray(size)
    values.copyInto(real)
    fft(real,imaginary,false)
    for(i in real.indices) { real[i]=real[i]*real[i]+imaginary[i]*imaginary[i]; imaginary[i]=0.0 }
    fft(real,imaginary,true)
    return real.copyOfRange(0,maxLag+1)
}

private fun fft(real: DoubleArray,imaginary: DoubleArray,inverse: Boolean) {
    val size=real.size
    var j=0
    for(i in 1 until size) {
        var bit=size ushr 1
        while(j and bit != 0) { j=j xor bit; bit=bit ushr 1 }
        j=j xor bit
        if(i<j) {
            var value=real[i]; real[i]=real[j]; real[j]=value
            value=imaginary[i]; imaginary[i]=imaginary[j]; imaginary[j]=value
        }
    }
    var length=2
    while(length<=size) {
        val angle=(if(inverse) 2 else -2)*PI/length
        val rootR=cos(angle); val rootI=sin(angle)
        val half=length/2
        var start=0
        while(start<size) {
            var weightR=1.0; var weightI=0.0
            for(offset in 0 until half) {
                val a=start+offset; val b=a+half
                val r=real[b]*weightR-imaginary[b]*weightI
                val im=real[b]*weightI+imaginary[b]*weightR
                real[b]=real[a]-r; imaginary[b]=imaginary[a]-im
                real[a]+=r; imaginary[a]+=im
                val nextR=weightR*rootR-weightI*rootI
                weightI=weightR*rootI+weightI*rootR; weightR=nextR
            }
            start+=length
        }
        length=length shl 1
    }
    if(inverse) for(i in real.indices) { real[i]/=size; imaginary[i]/=size }
}
