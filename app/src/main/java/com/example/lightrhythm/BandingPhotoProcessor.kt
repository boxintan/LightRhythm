package com.example.lightrhythm

import android.graphics.Bitmap
import com.example.lightrhythm.detection.detectBanding
import com.example.lightrhythm.detection.detectMoire
import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.ColorBandingEvidence
import com.example.lightrhythm.repair.ArgbPlanes
import com.example.lightrhythm.repair.BandingAxis
import com.example.lightrhythm.repair.BandingRepair
import com.example.lightrhythm.repair.ChromaBandingRepair
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext
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
        val colorEvidence = detection.colorEvidence
        if (colorEvidence?.isSuspected == true) {
            // Colour repair gets the first chance. A projector screen can also look like moire,
            // so checking moire first would block the repair on exactly the photos that need it.
            // The fit refuses on its own when the reported period explains no real modulation.
            val colourResult = repairColorBanding(source, colorEvidence, strength, context)
            if (colourResult is ProcessingResult.Preview) return colourResult
        }
        if (detectMoire(source).isSuspected) {
            return ProcessingResult.NotApplied("疑似摩尔纹暂不支持修复，未生成修复预览。")
        }
        context.ensureActive()
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

    /**
     * Colour paths cannot be repaired by scaling brightness, so they get their own route:
     * A owns the detection and its period, B fits per-channel gains at that period, and the
     * preview is only produced when the fit finds real colour modulation to remove.
     */
    private fun repairColorBanding(
        source: Bitmap,
        evidence: ColorBandingEvidence,
        strength: Float,
        context: CoroutineContext,
    ): ProcessingResult {
        val candidate = evidence.channels
            .filter { it.status == BandingStatus.SUSPECTED }
            .maxByOrNull { it.score }
            ?: return ProcessingResult.NotApplied("颜色横带证据不足，未生成修复预览。")
        val periodRows = candidate.candidatePeriodRows
            ?: return ProcessingResult.NotApplied("颜色横带周期不可用，未生成修复预览。")

        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val outcome = ChromaBandingRepair.repairWithPeriod(
            planes = ArgbPlanes.toPlanes(pixels),
            width = width,
            height = height,
            periodPx = periodRows.toDouble(),
            strength = strength,
            axis = BandingAxis.HORIZONTAL,
        )
        context.ensureActive()
        if (!outcome.applied) {
            return ProcessingResult.NotApplied("颜色横带检测与修复估计未达成一致，未生成修复预览。")
        }
        val corrected = ArgbPlanes.fromPlanes(outcome.planes!!, pixels)
        if (corrected.contentEquals(pixels)) {
            return ProcessingResult.NotApplied("当前参数未产生像素变化，未生成修复预览。")
        }
        return ProcessingResult.Preview(Bitmap.createBitmap(corrected, width, height, Bitmap.Config.ARGB_8888))
    }
}
