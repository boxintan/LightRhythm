package com.example.lightrhythm

import android.graphics.Bitmap
import com.example.lightrhythm.detection.detectBanding
import com.example.lightrhythm.detection.detectMoire
import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.repair.BandingAxis
import com.example.lightrhythm.repair.BandingRepair
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** Bridges A's Bitmap contract to B's normalized luminance arrays without forcing an estimate. */
internal object BandingPhotoProcessor : PhotoProcessor {
    override suspend fun process(source: Bitmap, strength: Float): ProcessingResult {
        require(strength.isFinite() && strength in 0f..1f)
        val context = currentCoroutineContext()
        context.ensureActive()
        if (strength == 0f) return ProcessingResult.NotApplied("修复强度为零，未生成修复预览。")
        val detection = detectBanding(source)
        context.ensureActive()
        if (detection.localBandingEvidence != null) {
            return ProcessingResult.NotApplied("局部或倾斜横纹暂不支持修复，未生成修复预览。")
        }
        if (detectMoire(source).isSuspected) {
            return ProcessingResult.NotApplied("疑似摩尔纹暂不支持修复，未生成修复预览。")
        }
        context.ensureActive()
        if (detection.colorEvidence?.isSuspected == true) {
            return ProcessingResult.NotApplied("颜色横带暂不支持修复，未生成修复预览。")
        }
        if (detection.status != BandingStatus.SUSPECTED) {
            return ProcessingResult.NotApplied("未发现可修复的明确明暗横纹，未生成修复预览。")
        }
        val period = detection.candidatePeriodRows
        if (period == null || period !in 4..64) {
            return ProcessingResult.NotApplied("当前明暗横纹周期超出修复范围，未生成修复预览。")
        }
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = FloatArray(pixels.size) { i ->
            val pixel = pixels[i]
            (.299 * ((pixel ushr 16) and 255) + .587 * ((pixel ushr 8) and 255) + .114 * (pixel and 255)).toFloat() / 255f
        }
        // B independently validates its own estimate. Do not pass A's additive residual as a gain.
        val repaired = BandingRepair.repair(luma, width, height, strength, axisHint = BandingAxis.HORIZONTAL)
        context.ensureActive()
        val estimatedPeriod = repaired.estimate?.periodPx
        if (!repaired.applied || estimatedPeriod == null || kotlin.math.abs(estimatedPeriod - period) > period * .1) {
            return ProcessingResult.NotApplied("检测与修复估计未达成一致，未生成修复预览。")
        }
        var changed = false
        for (y in 0 until height) {
            context.ensureActive()
            for (x in 0 until width) {
                val i = y * width + x
                val old = pixels[i]
                val gain = if (luma[i] > 1e-6f) repaired.luma[i] / luma[i] else 1f
                check(gain.isFinite() && gain >= 0f)
                fun channel(shift: Int) = ((((old ushr shift) and 255) * gain).roundToInt()).coerceIn(0, 255)
                val updated = (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
                changed = changed || updated != old
                pixels[i] = updated
            }
        }
        if (!changed) return ProcessingResult.NotApplied("当前参数未产生像素变化，未生成修复预览。")
        return ProcessingResult.Preview(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
    }
}
