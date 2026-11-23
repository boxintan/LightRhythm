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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission

@Composable
internal fun PhotoImportRoute(model: PhotoViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) model.save() else model.permissionDenied()
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let(model::select)
    }
    PhotoImportScreen(
        bitmap = model.bitmap, loading = model.loading, error = model.error,
        processing = model.processing, processingMessage = model.processingMessage,
        repairedPreview = model.repairedPreview, decodeMs = model.decodeMs,
        saving = model.saving, saveMessage = model.saveMessage, modifier = modifier,
        detecting = model.detecting, detection = model.detection, detectionError = model.detectionError,
        showOriginal = model.showOriginal, onShowOriginalChange = model::showOriginal,
        onDetectPhoto = model::detectAgain,
        onSelectPhoto = {
            try {
                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            } catch (_: android.content.ActivityNotFoundException) {
                model.pickerUnavailable()
            }
        },
        onSavePhoto = {
            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else model.save()
        },
        onProcessPhoto = model::process
    )
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
    onProcessPhoto: () -> Unit = {},
    detecting: Boolean = false,
    detection: TimedDetectionResult? = null,
    detectionError: String? = null,
    onDetectPhoto: () -> Unit = {},
    showOriginal: Boolean = false,
    onShowOriginalChange: (Boolean) -> Unit = {}
) {
    val busy = loading || detecting || processing || saving
    val controls: @Composable (Modifier) -> Unit = { area ->
        Column(area.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("光律 LightRhythm", style = MaterialTheme.typography.headlineSmall)
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
            if (repairedPreview != null) {
                Text("修复前后对比", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = showOriginal, onClick = { onShowOriginalChange(true) },
                        enabled = !busy, label = { Text("原图") })
                    FilterChip(selected = !showOriginal, onClick = { onShowOriginalChange(false) },
                        enabled = !busy, label = { Text("修复图") })
                }
                Text(if (showOriginal) "当前显示：原图预览" else "当前显示：修复预览")
            }
            Button(onClick = onSavePhoto, enabled = bitmap != null && !busy) {
                Text(when {
                    saving -> "正在保存…"
                    repairedPreview != null -> "保存修复副本"
                    else -> "保存预览副本"
                })
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
            if (bitmap == null && !loading && error == null) Text("请选择一张照片开始。")
        }
    }
    val showingRepair = repairedPreview != null && !showOriginal
    val photo = if (showingRepair) repairedPreview else bitmap
    val preview: @Composable (Modifier) -> Unit = { area ->
        if (photo != null) Image(
            photo.asImageBitmap(), if (showingRepair) "修复预览" else "所选照片预览",
            area, contentScale = ContentScale.Fit
        )
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(16.dp)) {
        if (maxWidth > maxHeight && photo != null) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                controls(Modifier.weight(1f).fillMaxHeight())
                preview(Modifier.weight(1.3f).fillMaxHeight())
            }
        } else {
            val controlHeight = if (photo == null) maxHeight else maxHeight * 0.62f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.fillMaxWidth().heightIn(max = controlHeight))
                preview(Modifier.fillMaxWidth().weight(1f))
            }
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
