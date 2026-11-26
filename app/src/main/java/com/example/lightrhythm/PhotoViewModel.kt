package com.example.lightrhythm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhotoViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    private val context = application.applicationContext
    private val pipeline = PhotoProcessingPipeline(BandingPhotoProcessor)
    var neutralBackground by mutableStateOf(saved.get<Boolean>("neutralBackground") ?: false)
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var loading by mutableStateOf<Boolean>(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var processing by mutableStateOf<Boolean>(false)
        private set
    var processingMessage by mutableStateOf<String?>(null)
        private set
    var repairedPreview by mutableStateOf<Bitmap?>(null)
        private set
    var decodeMs by mutableStateOf<Long?>(null)
        private set
    var saving by mutableStateOf<Boolean>(false)
        private set
    var saveMessage by mutableStateOf<String?>(null)
        private set

    var detecting by mutableStateOf(false)
        private set
    var detection by mutableStateOf<TimedDetectionResult?>(null)
        private set
    var detectionError by mutableStateOf<String?>(null)
        private set
    var showOriginal by mutableStateOf(false)
        private set

    private val busy get() = loading || detecting || processing || saving

    init {
        saved.get<String>("selectedUri")?.let { select(Uri.parse(it)) }
    }

    fun select(uri: Uri) {
        if (busy) return
        saved["selectedUri"] = uri.toString()
        loading = true
        error = null
        bitmap = null
        clearRepair()
        detection = null
        detectionError = null
        decodeMs = null
        val started = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            val decoded = try {
                withContext(Dispatchers.IO) { decodePhotoPreview(context, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "无法读取照片，请重新选择手机上的本地照片。"
                return@launch
            } finally {
                loading = false
            }
            bitmap = decoded
            decodeMs = SystemClock.elapsedRealtime() - started
            analyze(decoded)
        }
    }

    fun detectAgain() {
        val source = bitmap ?: return
        if (busy) return
        clearRepair()
        viewModelScope.launch { analyze(source) }
    }

    fun changeNeutralBackground(value: Boolean) {
        if (busy || value == neutralBackground) return
        neutralBackground = value
        saved["neutralBackground"] = value
        detectAgain()
    }

    private suspend fun analyze(source: Bitmap) {
        detecting = true
        detection = null
        detectionError = null
        try {
            val assumeNeutralBackground = neutralBackground
            detection = PhotoDetectionPipeline {
                com.example.lightrhythm.detection.detectBanding(it, assumeNeutralBackground = assumeNeutralBackground)
            }.run(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detectionError = "无法检测这张照片，请重试或选择不透明的 JPG、PNG 照片。"
        } finally {
            detecting = false
        }
    }

    fun save() {
        val preview = repairedPreview ?: bitmap
        if (preview != null && !busy) {
            saving = true
            saveMessage = null
            viewModelScope.launch {
                try {
                    savePhotoPreview(context, preview)
                    saveMessage = "预览副本已保存到相册 Pictures/LightRhythm（${preview.width} × ${preview.height}）"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveMessage = "保存失败，请检查可用空间和存储权限后重试。"
                } finally {
                    saving = false
                }
            }
        }
    }

    fun process() {
        val source = bitmap ?: return
        if (busy) return
        if (detection?.result?.localizedStripes?.isNotEmpty() == true) {
            processingMessage = "局部条纹暂不支持修复，未生成修复预览。"
            return
        }
        if (detection?.result?.localBandingEvidence != null) {
            processingMessage = "局部或倾斜横纹暂不支持修复，未生成修复预览。"
            return
        }
        if (detection?.moire?.isSuspected == true) {
            processingMessage = "疑似摩尔纹暂不支持修复，未生成修复预览。"
            return
        }
        if (detection?.result?.broadColorEvidence != null) {
            processingMessage = "宽幅颜色横带暂不支持修复，未生成修复预览。"
            return
        }
        if (!busy) {
            processing = true
            clearRepair()
            viewModelScope.launch {
                try {
                    val timed = pipeline.run(source, strength = 1f)
                    when (val result = timed.result) {
                        ProcessingResult.NotIntegrated ->
                            processingMessage = "算法尚未接入，未生成修复结果。"
                        is ProcessingResult.NotApplied -> processingMessage = result.message
                        is ProcessingResult.Preview -> {
                            repairedPreview = result.bitmap
                            processingMessage = "修复预览耗时：${timed.elapsedMs} ms"
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    processingMessage = "处理失败，请重试。原始照片未被修改。"
                } finally {
                    processing = false
                }
            }
        }
    }

    private fun clearRepair() {
        repairedPreview = null
        showOriginal = false
        processingMessage = null
        saveMessage = null
    }

    fun showOriginal(value: Boolean) {
        if (!busy && repairedPreview != null) showOriginal = value
    }

    fun permissionDenied() {
        saveMessage = "未获得保存权限，未保存副本。照片导入仍可使用。"
    }

    fun pickerUnavailable() {
        error = "系统没有可用的照片选择器。"
    }
}
