package com.example.lightrhythm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import android.os.SystemClock
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission

@Composable
fun PhotoImportRoute(modifier: Modifier = Modifier, initialPhotoUri: String? = null) {
    val context = LocalContext.current.applicationContext
    var selectedUri by rememberSaveable { mutableStateOf(initialPhotoUri) }
    var selectionVersion by rememberSaveable { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf<String?>(null) }
    var repairedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var decodeMs by remember { mutableStateOf<Long?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var detection by remember { mutableStateOf<TimedDetectionResult?>(null) }
    var detectionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val saveCurrentPreview: () -> Unit = {
        val preview = repairedPreview ?: bitmap
        if (preview != null && !loading && !processing && !saving && !detecting) {
            saving = true
            saveMessage = null
            scope.launch {
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
    val writePermission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) saveCurrentPreview()
        else saveMessage = "未获得保存权限，未保存副本。照片导入仍可使用。"
    }
    val pipeline = remember { PhotoProcessingPipeline(BandingPhotoProcessor) }
    val detectionPipeline = remember { PhotoDetectionPipeline() }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        // Cancelling preserves the previous selection.
        if (uri != null) {
            selectedUri = uri.toString()
            selectionVersion++
        }
    }
    LaunchedEffect(selectedUri, selectionVersion) {
        val uri = selectedUri ?: return@LaunchedEffect
        val requestVersion = selectionVersion
        loading = true
        error = null
        bitmap = null
        repairedPreview = null
        processingMessage = null
        saveMessage = null
        decodeMs = null
        detection = null
        detectionError = null
        detecting = false
        val started = SystemClock.elapsedRealtime()
        try {
            val decoded = withContext(Dispatchers.IO) { decodePhotoPreview(context, Uri.parse(uri)) }
            bitmap = decoded
            decodeMs = SystemClock.elapsedRealtime() - started
            loading = false
            detecting = true
            detection = detectionPipeline.run(decoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (bitmap == null) error = "无法读取照片，请重新选择手机上的本地照片。"
            else detectionError = "无法检测这张照片，请重试或选择不透明的 JPG、PNG 照片。"
        } finally {
            if (selectedUri == uri && selectionVersion == requestVersion) {
                loading = false
                detecting = false
            }
        }
    }
    PhotoImportScreen(bitmap, loading, error, {
        try {
            picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        } catch (_: android.content.ActivityNotFoundException) {
            error = "系统没有可用的照片选择器。"
        }
    }, modifier, processing, processingMessage, repairedPreview, decodeMs,
        saving = saving, saveMessage = saveMessage, onSavePhoto = {
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED) {
                writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else saveCurrentPreview()
        }, detecting = detecting, detection = detection, detectionError = detectionError,
        onDetectPhoto = { selectionVersion++ }) {
        val source = bitmap ?: return@PhotoImportScreen
        if (!processing) {
            processing = true
            processingMessage = null
            repairedPreview = null
            scope.launch {
                try {
                    val timed = pipeline.run(source, strength = 1f)
                    when (val result = timed.result) {
                        ProcessingResult.NotIntegrated ->
                            processingMessage = "修复算法尚未接入，未生成修复结果。"
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
}

// Stateless UI: member D can replace the layout without changing photo loading.
@Composable
internal fun PhotoImportScreen(
    bitmap: Bitmap?, loading: Boolean, error: String?,
    onSelectPhoto: () -> Unit, modifier: Modifier = Modifier,
    processing: Boolean = false, processingMessage: String? = null,
    repairedPreview: Bitmap? = null, decodeMs: Long? = null,
    saving: Boolean = false, saveMessage: String? = null,
    onSavePhoto: () -> Unit = {},
    detecting: Boolean = false,
    detection: TimedDetectionResult? = null,
    detectionError: String? = null,
    onDetectPhoto: () -> Unit = {},
    onProcessPhoto: () -> Unit = {}
) {
    val busy = loading || processing || saving || detecting
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("光律 LightRhythm", style = MaterialTheme.typography.headlineSmall)
        Text("选择照片后自动检测横纹，可查看候选周期。")
        Button(onClick = onSelectPhoto, enabled = !busy) { Text("选择照片") }
        Button(onClick = onDetectPhoto, enabled = bitmap != null && !busy) {
            Text(if (detection == null) "检测横纹" else "重新检测")
        }
        if (detecting) {
            CircularProgressIndicator()
            Text("正在检测横纹…")
        }
        detectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        detection?.let { DetectionResultCard(it) }
        Button(onClick = onProcessPhoto, enabled = bitmap != null && !busy) {
            Text("生成修复预览")
        }
        Button(onClick = onSavePhoto, enabled = bitmap != null && !busy) {
            Text(if (saving) "正在保存…" else "保存预览副本")
        }
        Text("保存当前预览尺寸的 PNG 副本，不覆盖原图。", style = MaterialTheme.typography.bodySmall)
        saveMessage?.let { Text(it) }
        decodeMs?.let { Text("照片读取耗时：$it ms（不含修复）") }
        processingMessage?.let { Text(it) }
        if (processing) {
            CircularProgressIndicator()
            Text("正在处理…")
        }
        if (loading) {
            CircularProgressIndicator()
            Text("正在读取照片…")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (bitmap != null) {
            Image((repairedPreview ?: bitmap).asImageBitmap(), if (repairedPreview != null) "修复预览" else "所选照片预览", Modifier.fillMaxWidth().height(320.dp), contentScale = ContentScale.Fit)
        } else if (!loading && error == null) {
            Text("请选择一张照片开始。原始照片不会被修改。")
        }
    }
}

/** Decode only a bounded preview; never overwrite the source Uri. Call off the UI thread. */
internal fun decodePhotoPreview(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    val maxEdge = 1600
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    // Android 8.x fallback: sample before allocating pixels, then apply EXIF orientation.
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image" }
    var sample = 1
    while (maxOf(options.outWidth, options.outHeight) / sample > maxEdge) sample *= 2
    options.inJustDecodeBounds = false
    options.inSampleSize = sample
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Cannot decode image")
    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }
            8 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
}
