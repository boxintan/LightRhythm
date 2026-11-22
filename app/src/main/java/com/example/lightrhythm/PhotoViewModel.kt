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
    private val pipeline = PhotoProcessingPipeline()
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

    init {
        saved.get<String>("selectedUri")?.let { select(Uri.parse(it)) }
    }

    fun select(uri: Uri) {
        if (loading || processing || saving) return
        saved["selectedUri"] = uri.toString()
        loading = true
        error = null
        bitmap = null
        repairedPreview = null
        processingMessage = null
        saveMessage = null
        decodeMs = null
        val started = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                bitmap = withContext(Dispatchers.IO) { decodePhotoPreview(context, uri) }
                decodeMs = SystemClock.elapsedRealtime() - started
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "无法读取照片，请重新选择手机上的本地照片。"
            } finally {
                loading = false
            }
        }
    }

    fun save() {
        val preview = repairedPreview ?: bitmap
        if (preview != null && !loading && !processing && !saving) {
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
        if (!loading && !saving && !processing) {
            processing = true
            processingMessage = null
            repairedPreview = null
            viewModelScope.launch {
                try {
                    val timed = pipeline.run(source, strength = 1f)
                    when (val result = timed.result) {
                        ProcessingResult.NotIntegrated ->
                            processingMessage = "算法尚未接入，未生成修复结果。"
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

    fun permissionDenied() {
        saveMessage = "未获得保存权限，未保存副本。照片导入仍可使用。"
    }

    fun pickerUnavailable() {
        error = "系统没有可用的照片选择器。"
    }
}
