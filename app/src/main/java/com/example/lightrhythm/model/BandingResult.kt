package com.example.lightrhythm.model

/** 初始工程阈值，尚未用真实照片标定。周期单位是输入 Bitmap 的像素行。 */
data class DetectionConfig(
    val minPeriodRows: Int = 4,
    val maxPeriodRows: Int = 256,
    val minCycles: Int = 4,
    val minRmsAmplitude: Double = 2.0,
    val minAutocorrelation: Double = 0.65,
    val minRegionSupport: Double = 0.75,
) {
    init {
        require(minPeriodRows >= 4 && maxPeriodRows in minPeriodRows..1024)
        require(minCycles >= 4)
        require(minRmsAmplitude.isFinite() && minRmsAmplitude > 0)
        require(minAutocorrelation in 0.5..1.0)
        require(minRegionSupport in 0.5..1.0)
    }
}

enum class BandingStatus {
    SUSPECTED,
    NO_CLEAR_PERIODICITY,
    LOW_AMPLITUDE,
    INCONSISTENT_REGIONS,
    INSUFFICIENT_DATA,
    /** Color-only candidate; consult colorEvidence, do not use luminance row offsets for repair. */
    SUSPECTED_COLOR_BANDING,
    SUSPECTED_BROAD_COLOR_BAND,
}

/** x 范围为 [startX, endXExclusive)，均使用输入图片坐标。 */
data class RegionEvidence(
    val startX: Int,
    val endXExclusive: Int,
    val rmsAmplitude: Double,
    val autocorrelation: Double,
    val agreementWithWholeImage: Double,
    val supportsCandidate: Boolean,
)

/**
 * A/B/C 接口草案。SUSPECTED 仅表示出现跨区域一致的周期性明暗变化，不能确认 LED 成因。
 * score 为 0..1 的启发式指标，不是概率或已标定置信度。
 * rowResidual 含真实场景成分；candidateRowOffsets 也仅供诊断，不是可直接用于修复的增益。
 * 所有行序列均对应本输入 Bitmap 的行坐标，不自动映射原图或缩略图。
 */
data class BandingResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val status: BandingStatus,
    val score: Double,
    val candidatePeriodRows: Int?,
    val rmsAmplitude: Double,
    val autocorrelation: Double,
    val regionSupport: Double,
    val rowLuminance: List<Double>,
    val rowTrend: List<Double>,
    val rowResidual: List<Double>,
    val candidateRowOffsets: List<Double>,
    val regions: List<RegionEvidence>,
    val config: DetectionConfig,
    val algorithmVersion: String = "row-periodicity-v2",
    /** Luminance fields above retain their original meaning even for a color-only candidate. */
    val colorEvidence: ColorBandingEvidence? = null,
    val broadColorEvidence: BroadColorEvidence? = null,
) {
    val isSuspected: Boolean get() = status == BandingStatus.SUSPECTED ||
        status == BandingStatus.SUSPECTED_COLOR_BANDING || status == BandingStatus.SUSPECTED_BROAD_COLOR_BAND
}

/** Only populated when the caller confirms the source content should be white/gray. */
data class BroadColorEvidence(val channel: String, val chromaSpread: Double, val regionSupport: Double, val validBinFraction: Double)

/** Periods use input rows; trendRadiusRows is scaled from referenceHeightRows unless null. */
data class ColorDetectionConfig(
    val enabled: Boolean = true,
    val maxPeriodRows: Int = 1024,
    val minCycles: Int = 2,
    val trendRadiusRows: Int = 256,
    val referenceHeightRows: Int? = 1440,
) {
    init {
        require(maxPeriodRows in 4..1024)
        require(minCycles >= 2)
        require(trendRadiusRows > 0 && trendRadiusRows <= 1024)
        require(referenceHeightRows == null || referenceHeightRows >= 64)
    }
}

/** Channel differences are encoded as (channel - green) / 2 + 127.5, range 0..255. */
data class ColorChannelEvidence(
    val channel: String,
    val status: BandingStatus,
    val candidatePeriodRows: Int?,
    val score: Double,
    val rmsAmplitude: Double,
    val autocorrelation: Double,
    val regionSupport: Double,
    val regions: List<RegionEvidence>,
)

data class ColorBandingEvidence(
    val channels: List<ColorChannelEvidence>,
    val config: ColorDetectionConfig,
    val luminanceStatus: BandingStatus,
    val effectiveTrendRadiusRows: Int = config.trendRadiusRows,
    val effectiveMaxLagRows: Int = 0,
) {
    val isSuspected: Boolean get() = channels.any { it.status == BandingStatus.SUSPECTED }
}
