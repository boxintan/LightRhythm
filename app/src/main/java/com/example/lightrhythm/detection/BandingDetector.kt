package com.example.lightrhythm.detection

import android.graphics.Bitmap
import com.example.lightrhythm.model.BandingResult
import com.example.lightrhythm.model.DetectionConfig
import com.example.lightrhythm.model.ColorDetectionConfig

/**
 * A 的检测入口：软件、不透明、方向正确的 Bitmap；由 C 在后台线程调用。
 * 仅读取输入，按四个纵向区域统计；不确认 LED 成因。
 * assumeNeutralBackground 仅在调用方确认原画面应为白色/灰色时开启，用于非周期宽幅色带。
 */
fun detectBanding(
    image: Bitmap,
    config: DetectionConfig = DetectionConfig(),
    colorConfig: ColorDetectionConfig = ColorDetectionConfig(),
    assumeNeutralBackground: Boolean = false,
): BandingResult {
    require(!image.isRecycled) { "图片已经被释放" }
    require(image.config != Bitmap.Config.HARDWARE) { "请使用可读取像素的软件 Bitmap" }
    val statistics = readDetectionProfiles(image.width, image.height) { y, pixels ->
        image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)
    }
    val periodic = analyzeDetectionProfiles(statistics, config, colorConfig)
    if (!assumeNeutralBackground) return periodic
    val broad = analyzeBroadColorBanding(image.width, image.height) { y, pixels ->
        image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)
    }
    return periodic.copy(
        status = if (!periodic.isSuspected && broad != null) com.example.lightrhythm.model.BandingStatus.SUSPECTED_BROAD_COLOR_BAND else periodic.status,
        broadColorEvidence = broad, algorithmVersion = "row-color-broad-v5",
    )
}

internal data class RegionProfiles(val rows: List<DoubleArray>, val widths: List<Int>)

internal fun readRegionProfiles(
    width: Int, height: Int, readRow: (y: Int, pixels: IntArray) -> Unit,
): RegionProfiles = readDetectionProfiles(width, height, readRow).luminance

internal fun readDetectionProfiles(
    width: Int, height: Int, readRow: (y: Int, pixels: IntArray) -> Unit,
): DetectionProfiles {
    require(width > 0 && height > 0)
    val count = minOf(4, width)
    val boundaries = IntArray(count + 1) { (width.toLong() * it / count).toInt() }
    val widths = List(count) { boundaries[it + 1] - boundaries[it] }
    val rows = List(count) { DoubleArray(height) }
    val redGreen = List(count) { DoubleArray(height) }
    val blueGreen = List(count) { DoubleArray(height) }
    val pixels = IntArray(width)
    for (y in 0 until height) {
        readRow(y, pixels)
        for (region in 0 until count) {
            var sum = 0.0
            var rg = 0.0
            var bg = 0.0
            for (x in boundaries[region] until boundaries[region + 1]) {
                val pixel = pixels[x]
                sum += pixelLuminance(pixel)
                val green = (pixel ushr 8) and 255
                rg += (((pixel ushr 16) and 255) - green) / 2.0 + 127.5
                bg += ((pixel and 255) - green) / 2.0 + 127.5
            }
            rows[region][y] = sum / widths[region]
            redGreen[region][y] = rg / widths[region]
            blueGreen[region][y] = bg / widths[region]
        }
    }
    return DetectionProfiles(RegionProfiles(rows, widths), redGreen, blueGreen)
}
