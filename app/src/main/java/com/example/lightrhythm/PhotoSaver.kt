package com.example.lightrhythm

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Writes a new PNG preview, never the source Uri. Legacy callers must obtain write permission. */
internal suspend fun savePhotoPreview(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = "LightRhythm_preview_${UUID.randomUUID()}.png"
    var legacyFile: File? = null
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LightRhythm")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LightRhythm")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create album" }
            legacyFile = File(directory, name)
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("Cannot create media entry")
    try {
        resolver.openOutputStream(uri, "w")?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Image encoding failed" }
        } ?: error("Cannot open output")
        ensureActive()
        if (Build.VERSION.SDK_INT >= 29) {
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish image" }
        }
        uri
    } catch (failure: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        legacyFile?.delete()
        throw failure
    }
}
