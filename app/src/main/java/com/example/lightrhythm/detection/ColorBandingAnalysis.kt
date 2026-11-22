package com.example.lightrhythm.detection

import com.example.lightrhythm.model.BandingResult
import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.ColorBandingEvidence
import com.example.lightrhythm.model.ColorChannelEvidence
import com.example.lightrhythm.model.ColorDetectionConfig
import com.example.lightrhythm.model.DetectionConfig

internal data class DetectionProfiles(
    val luminance: RegionProfiles,
    val redGreen: List<DoubleArray>,
    val blueGreen: List<DoubleArray>,
)

internal fun analyzeDetectionProfiles(
    profiles: DetectionProfiles,
    config: DetectionConfig = DetectionConfig(),
    colorConfig: ColorDetectionConfig = ColorDetectionConfig(),
): BandingResult {
    val luminance = analyzeBandingProfiles(profiles.luminance.rows, profiles.luminance.widths, config)
    if (!colorConfig.enabled) return luminance
    val height = luminance.imageHeight
    if (height < 64) return luminance.copy(algorithmVersion = "row-color-periodicity-v2")
    val searchMaxLag = minOf(colorConfig.maxPeriodRows, height / colorConfig.minCycles)
    if (searchMaxLag < config.minPeriodRows) return luminance.copy(algorithmVersion = "row-color-periodicity-v2")
    val channels = listOf("R-G" to profiles.redGreen, "B-G" to profiles.blueGreen).map { (name, rows) ->
        val candidate = analyzeBandingProfiles(
            rows, profiles.luminance.widths, config, searchMaxLag, colorConfig.trendRadiusRows,
        )
        ColorChannelEvidence(name, candidate.status, candidate.candidatePeriodRows, candidate.score,
            candidate.rmsAmplitude, candidate.autocorrelation, candidate.regionSupport, candidate.regions)
    }
    val evidence = ColorBandingEvidence(channels, colorConfig, luminance.status)
    return luminance.copy(
        status = if (!luminance.isSuspected && evidence.isSuspected) BandingStatus.SUSPECTED_COLOR_BANDING else luminance.status,
        algorithmVersion = "row-color-periodicity-v2", colorEvidence = evidence,
    )
}
