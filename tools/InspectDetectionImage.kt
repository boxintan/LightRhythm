package com.example.lightrhythm.detection

import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/** 桌面诊断工具，不属于 Android App。读取原始文件像素，不处理 EXIF，不修改或另存图片。 */
fun main(args: Array<String>) {
    require(args.size == 1) { "请传入一张本地图片路径" }
    val image = ImageIO.read(File(args[0])) ?: error("无法解码图片")
    val start = System.nanoTime()
    val result = analyzeImageBanding(image.width, image.height) { y, pixels ->
        image.getRGB(0, y, image.width, 1, pixels, 0, image.width)
    }
    val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
    println("SAMPLE_DIAGNOSTICS (desktop pixels, not Android device validation)")
    println("width=${result.imageWidth}, height=${result.imageHeight}, status=${result.status}")
    println("localStripeCells=${result.localBandingEvidence?.tiles?.size ?: 0}")
    println(String.format(Locale.ROOT,
        "score=%.6f, periodRows=%s, rms=%.6f, autocorrelation=%.6f, regionSupport=%.6f, desktopMs=%.3f",
        result.score, result.candidatePeriodRows, result.rmsAmplitude,
        result.autocorrelation, result.regionSupport, elapsedMs))
    result.regions.forEachIndexed { index, region ->
        println(String.format(Locale.ROOT,
            "region=%d, x=[%d,%d), rms=%.6f, correlation=%.6f, agreement=%.6f, supports=%s",
            index, region.startX, region.endXExclusive, region.rmsAmplitude,
            region.autocorrelation, region.agreementWithWholeImage, region.supportsCandidate))
    }
    result.colorEvidence?.let { println("colorTrendRadiusRows=${it.effectiveTrendRadiusRows}, colorMaxLagRows=${it.effectiveMaxLagRows}") }
    result.colorEvidence?.channels?.forEach { channel ->
        println("colorChannel=${channel.channel}, status=${channel.status}, period=${channel.candidatePeriodRows}, " +
            "rms=${channel.rmsAmplitude}, correlation=${channel.autocorrelation}, support=${channel.regionSupport}")
        channel.regions.forEachIndexed { index, region ->
            println("  region=$index correlation=${region.autocorrelation} agreement=${region.agreementWithWholeImage} supports=${region.supportsCandidate}")
        }
    }
}
