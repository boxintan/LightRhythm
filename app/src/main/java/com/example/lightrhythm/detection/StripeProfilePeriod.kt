package com.example.lightrhythm.detection

import com.example.lightrhythm.model.DetectionConfig
import kotlin.math.sqrt

internal data class StripeProfilePeriod(val pixels: Int,val correlation: Double)

/** One real strip contributes one vote. Thin pulse trains need not reverse at half a cycle. */
internal fun stripeProfilePeriod(profile: DoubleArray,config: DetectionConfig,
    minimumCorrelation: Double = config.minAutocorrelation): StripeProfilePeriod? {
    if(profile.size < 32) return null
    val maxLag=minOf(config.maxPeriodRows,profile.size/config.minCycles)
    if(maxLag < config.minPeriodRows) return null
    val filtered=DoubleArray(profile.size) { (profile[maxOf(0,it-1)]+2*profile[it]+profile[minOf(profile.lastIndex,it+1)])/4 }
    val trend=localLinearTrend(filtered,maxLag)
    val residual=DoubleArray(profile.size) { filtered[it]-trend[it] }
    val rms=sqrt(residual.sumOf { it*it }/profile.size)
    if(rms < config.minRmsAmplitude) return null
    // Limit isolated glyph/edge excursions while measuring amplitude in the original units.
    val bounded=DoubleArray(residual.size) { residual[it].coerceIn(-2*rms,2*rms) }
    val correlations=DoubleArray(maxLag+2) { correlation(bounded,bounded,it) }
    val threshold=minimumCorrelation
    val candidates=(config.minPeriodRows..maxLag).filter { period ->
        correlations[period] >= threshold && correlations[period] >= correlations[period-1] &&
            correlations[period] > correlations[period+1] && correlations[period/2] <= .25 &&
            correlations[period]-correlations[period/2] >= .5 &&
            (2 until config.minPeriodRows).none { shorter ->
                period%shorter==0 && correlations[shorter] >= threshold &&
                    correlations[shorter] >= correlations[period]-.03 &&
                    correlations[shorter] >= correlations[shorter-1] && correlations[shorter] > correlations[shorter+1]
            }
    }
    if(candidates.isEmpty()) return null
    val best=candidates.maxOf { correlations[it] }
    val period=candidates.first { correlations[it] >= best-.03 }
    return StripeProfilePeriod(period,correlations[period])
}
