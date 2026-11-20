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

@Composable
fun PhotoImportRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionVersion by rememberSaveable { mutableStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var processingMessage by remember { mutableStateOf<String?>(null) }
    var repairedPreview by remember { mutableStateOf<Bitmap?>(null) }
    var decodeMs by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val pipeline = remember { PhotoProcessingPipeline() }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        // Cancelling preserves the previous selection.
        if (uri != null) {
            selectedUri = uri.toString()
            selectionVersion++
        }
    }
    LaunchedEffect(selectedUri, selectionVersion) {
        val uri = selectedUri ?: return@LaunchedEffect
        loading = true
        error = null
        bitmap = null
        repairedPreview = null
        processingMessage = null
        decodeMs = null
        val started = SystemClock.elapsedRealtime()
        try {
            bitmap = withContext(Dispatchers.IO) { decodePhotoPreview(context, Uri.parse(uri)) }
            decodeMs = SystemClock.elapsedRealtime() - started
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "无法读取照片，请重新选择手机上的本地照片。"
        } finally {
            loading = false
        }
    }
    PhotoImportScreen(bitmap, loading, error, {
        try {
            picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        } catch (_: android.content.ActivityNotFoundException) {
            error = "系统没有可用的照片选择器。"
        }
    }, modifier, processing, processingMessage, repairedPreview, decodeMs) {
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
}

// Stateless UI: member D can replace the layout without changing photo loading.
@Composable
fun PhotoImportScreen(
    bitmap: Bitmap?, loading: Boolean, error: String?,
    onSelectPhoto: () -> Unit, modifier: Modifier = Modifier,
    processing: Boolean = false, processingMessage: String? = null,
    repairedPreview: Bitmap? = null, decodeMs: Long? = null,
    onProcessPhoto: () -> Unit = {}
) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("光律 LightRhythm", style = MaterialTheme.typography.headlineSmall)
        Text("导入本地照片 · 当前仅显示预览，尚未进行横纹修复")
        Button(onClick = onSelectPhoto, enabled = !loading && !processing) { Text("选择照片") }
        Button(onClick = onProcessPhoto, enabled = bitmap != null && !loading && !processing) {
            Text("生成修复预览")
        }
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
            Image((repairedPreview ?: bitmap).asImageBitmap(), if (repairedPreview != null) "修复预览" else "所选照片预览", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
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
