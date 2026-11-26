package com.example.lightrhythm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lightrhythm.model.BandingStatus
import com.example.lightrhythm.model.StripeAxis

@Composable
internal fun DetectionResultCard(detection: TimedDetectionResult, modifier: Modifier = Modifier) {
    val result = detection.result
    val color = result.colorEvidence?.channels
        ?.filter { it.status == BandingStatus.SUSPECTED }
        ?.maxByOrNull { it.score }
    val heading = when (result.status) {
        BandingStatus.SUSPECTED -> if (color != null) "疑似明暗横纹及颜色横带" else "疑似明暗横纹"
        BandingStatus.SUSPECTED_COLOR_BANDING -> "疑似颜色横带"
        BandingStatus.SUSPECTED_BROAD_COLOR_BAND -> "疑似宽幅色带"
        BandingStatus.SUSPECTED_LOCAL_BANDING -> when {
            result.localizedStripes.isEmpty() -> "疑似局部横纹"
            result.localizedStripes.all { it.axis == StripeAxis.VERTICAL } -> "疑似局部竖纹"
            result.localizedStripes.all { it.axis == StripeAxis.HORIZONTAL } -> "疑似局部横纹"
            else -> "疑似局部条纹"
        }
        BandingStatus.LOW_AMPLITUDE, BandingStatus.NO_CLEAR_PERIODICITY -> "未发现明确的周期横纹"
        BandingStatus.INCONSISTENT_REGIONS -> "存在局部周期变化，暂不能确认横纹"
        BandingStatus.INSUFFICIENT_DATA -> "图片尺寸不足，暂时无法判断"
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.titleMedium)
            result.localBandingEvidence?.let {
                Text("多个局部区域存在重复明暗条纹，可能有倾斜或只覆盖部分画面；暂不支持这类条纹的修复。")
            }
            if(result.localizedStripes.isNotEmpty()) {
                Text("局部区域存在重复条纹，原有内容纹理也可能产生相似结果；暂不支持这类条纹的修复。")
            }
            result.localizedStripes.take(3).forEach { region ->
                val direction=if(region.axis==StripeAxis.HORIZONTAL) "横向" else "竖向"
                val unit=if(region.axis==StripeAxis.HORIZONTAL) "像素行" else "像素列"
                Text("局部条纹：$direction · 候选周期 ${region.periodPixels} $unit")
            }
            detection.moire?.let { moire ->
                Text(when {
                    moire.testedTiles == 0 -> "图片尺寸不足，暂无法检测摩尔纹"
                    moire.isSuspected -> "疑似摩尔纹"
                    else -> "未发现明确的彩色摩尔纹特征"
                },
                    style = MaterialTheme.typography.titleMedium)
                if (moire.isSuspected) Text("多个局部区域存在重复彩色细纹；原有彩色纹理也可能产生相似结果，暂不支持摩尔纹修复。")
            }
            result.broadColorEvidence?.let { Text("白色/灰色画面中检测到横向颜色变化，不要求重复周期。") }
            if (result.status == BandingStatus.SUSPECTED) {
                result.candidatePeriodRows?.let { Text("明暗候选周期：$it 像素行") }
            }
            color?.candidatePeriodRows?.let { Text("颜色候选周期：$it 像素行") }
            Text("基于预览图 ${result.imageWidth} × ${result.imageHeight} · 检测耗时 ${detection.elapsedMs} ms",
                style = MaterialTheme.typography.bodySmall)
            Text("检测完成，原图未修改。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
