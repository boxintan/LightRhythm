package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingResult
import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.RegionEvidence
import kotlin.math.sqrt

/** 纯 Kotlin 分析入口，便于独立验证计算；每个序列对应一个纵向区域的逐行均值。 */
internal fun analyzeBandingProfiles(
    profiles: List<DoubleArray>,
    regionWidths: List<Int>,
    config: DetectionConfig = DetectionConfig(),
    searchMaxLag: Int? = null,
    trendRadius: Int? = null,
): BandingResult {
    require(profiles.isNotEmpty() && profiles.size == regionWidths.size)
    val height = profiles.first().size
    require(height > 0 && profiles.all { it.size == height })
    require(regionWidths.all { it > 0 })
    val widthLong = regionWidths.sumOf { it.toLong() }
    require(widthLong <= Int.MAX_VALUE)
    require(profiles.all { rows -> rows.all { it.isFinite() && it in 0.0..255.0 } })
    val width = widthLong.toInt()
    // 区域宽度可能不相同，必须按像素数量加权，不能简单平均四块。
    val raw = DoubleArray(height) { y ->
        profiles.indices.sumOf { profiles[it][y] * regionWidths[it].toDouble() / width }
    }
    require(searchMaxLag == null || searchMaxLag in 1..height / 2)
    require(trendRadius == null || trendRadius > 0)
    val maxLag = searchMaxLag ?: minOf(config.maxPeriodRows, height / config.minCycles)
    val radius = trendRadius ?: maxOf(1, maxLag)
    val trend = localLinearTrend(raw, radius)
    val residual = DoubleArray(height) { raw[it] - trend[it] }
    val amplitude = rms(residual)

    fun result(
        status: BandingStatus,
        period: Int? = null,
        peak: Double = 0.0,
        support: Double = 0.0,
        evidence: List<RegionEvidence> = emptyList(),
    ): BandingResult {
        val suspected = status == BandingStatus.SUSPECTED
        return BandingResult(
            imageWidth = width, imageHeight = height, status = status,
            score = (peak.coerceAtLeast(0.0) * (amplitude / 8.0).coerceIn(0.0, 1.0) * support)
                .coerceIn(0.0, 1.0),
            candidatePeriodRows = period, rmsAmplitude = amplitude,
            autocorrelation = peak, regionSupport = support,
            rowLuminance = raw.toList(), rowTrend = trend.toList(), rowResidual = residual.toList(),
            candidateRowOffsets = if (suspected && period != null) foldPeriodicOffsets(residual, period) else emptyList(),
            regions = evidence, config = config,
        )
    }

    // 至少四个有宽度的区域，避免把极窄图片当作跨区域证据。
    if (height < 64 || profiles.size != 4 || regionWidths.any { it < 8 } || maxLag < config.minPeriodRows) {
        return result(BandingStatus.INSUFFICIENT_DATA)
    }
    if (amplitude < config.minRmsAmplitude) return result(BandingStatus.LOW_AMPLITUDE)

    val candidate=selectBandingPeriod(residual,maxLag,config) ?: return result(BandingStatus.NO_CLEAR_PERIODICITY)
    val period=candidate.period
    val peak=candidate.correlation
    var x = 0
    val evidence = profiles.mapIndexed { index, rows ->
        val regionTrend = localLinearTrend(rows, radius)
        val regionResidual = DoubleArray(height) { rows[it] - regionTrend[it] }
        val regionAmplitude = rms(regionResidual)
        val regionPeak = correlation(regionResidual, regionResidual, period)
        val agreement = correlation(regionResidual, residual)
        val supports = regionAmplitude >= config.minRmsAmplitude &&
            regionPeak >= config.minAutocorrelation && agreement >= 0.7 &&
            correlation(regionResidual, regionResidual, period / 2) <= -0.15
        RegionEvidence(x, x + regionWidths[index], regionAmplitude, regionPeak, agreement, supports)
            .also { x += regionWidths[index] }
    }
    val support = evidence.count { it.supportsCandidate }.toDouble() / evidence.size
    val status = if (support >= config.minRegionSupport) BandingStatus.SUSPECTED else BandingStatus.INCONSISTENT_REGIONS
    return result(status, period, peak, support, evidence)
}

internal data class BandingPeriod(val period: Int,val correlation: Double)

/** Shared selection rules; local scans need no boxed row reports or whole-image phase checks. */
internal fun selectBandingPeriod(residual: DoubleArray,maxLag: Int,config: DetectionConfig): BandingPeriod? {
    val correlations=periodSearchCorrelations(residual,maxLag,config.minPeriodRows,config.minAutocorrelation)
    val candidates=IntArray(maxLag+1)
    var count=0
    var bestPeak=Double.NEGATIVE_INFINITY
    for(period in config.minPeriodRows..maxLag) if(
        correlations[period] >= config.minAutocorrelation &&
            correlations[period] >= correlations[period - 1] &&
            correlations[period] > correlations[period + 1] &&
            // 半周期应有明暗反转，单纯平滑变化的高相关不足以作为周期证据。
            correlations[period / 2] <= -0.15 &&
            // A short repeating texture also correlates at 3x, 5x, ... its period.
            // Do not allow these aliases to bypass the configured minimum period.
            (2 until config.minPeriodRows).none { shorter ->
                period % shorter == 0 &&
                    correlations[shorter] >= config.minAutocorrelation &&
                    correlations[shorter] >= correlations[period] - 0.03 &&
                    correlations[shorter] >= correlations[shorter - 1] &&
                    correlations[shorter] > correlations[shorter + 1]
            }
    ) {
        candidates[count++]=period
        if(correlations[period]>bestPeak) bestPeak=correlations[period]
    }
    if(count==0) return null
    // 相关度接近时取最短周期，减少选择基频整数倍的情况。
    val period=(0 until count).first { correlations[candidates[it]]>=bestPeak-.03 }.let { candidates[it] }
    return BandingPeriod(period,correlations[period])
}

/** 用滑动局部直线拟合估计缓慢趋势；截断边界也拟合直线，避免给纯渐变引入假条带。 */
internal fun localLinearTrend(values: DoubleArray, radius: Int): DoubleArray {
    require(values.isNotEmpty() && radius > 0)
    val n = values.size
    val sumY = DoubleArray(n + 1)
    val sumXY = DoubleArray(n + 1)
    for (i in values.indices) {
        sumY[i + 1] = sumY[i] + values[i]
        sumXY[i + 1] = sumXY[i] + i.toDouble() * values[i]
    }
    fun sumSquaresBefore(end: Int): Double {
        val last = end.toDouble() - 1.0
        return last * (last + 1.0) * (2.0 * last + 1.0) / 6.0
    }
    return DoubleArray(n) { i ->
        val start = maxOf(0, i - radius)
        val end = minOf(n.toLong(), i.toLong() + radius + 1L).toInt()
        val count = (end - start).toDouble()
        val meanX = (start.toDouble() + end - 1.0) / 2.0
        val y = sumY[end] - sumY[start]
        val xy = sumXY[end] - sumXY[start]
        val varianceX = sumSquaresBefore(end) - sumSquaresBefore(start) - count * meanX * meanX
        val slope = if (varianceX > 1e-12) (xy - meanX * y) / varianceX else 0.0
        y / count + slope * (i - meanX)
    }
}

/** 对有效重叠区间计算 Pearson 相关，不补零；零方差返回 0。 */
internal fun correlation(first: DoubleArray, second: DoubleArray, lag: Int = 0): Double {
    require(first.size == second.size && lag >= 0)
    val count = first.size - lag
    if (count < 2) return 0.0
    var sx = 0.0
    var sy = 0.0
    for (i in 0 until count) { sx += first[i]; sy += second[i + lag] }
    val mx = sx / count
    val my = sy / count
    var xx = 0.0
    var yy = 0.0
    var xy = 0.0
    for (i in 0 until count) {
        val x = first[i] - mx
        val y = second[i + lag] - my
        xx += x * x; yy += y * y; xy += x * y
    }
    return if (xx <= 1e-12 || yy <= 1e-12) 0.0 else (xy / sqrt(xx * yy)).coerceIn(-1.0, 1.0)
}

private fun rms(values: DoubleArray): Double = sqrt(values.sumOf { it * it } / values.size)

private fun foldPeriodicOffsets(residual: DoubleArray, period: Int): List<Double> {
    val template = DoubleArray(period)
    val counts = IntArray(period)
    residual.forEachIndexed { y, value -> template[y % period] += value; counts[y % period]++ }
    for (phase in template.indices) template[phase] /= counts[phase]
    val mean = template.average()
    return List(residual.size) { template[it % period] - mean }
}
