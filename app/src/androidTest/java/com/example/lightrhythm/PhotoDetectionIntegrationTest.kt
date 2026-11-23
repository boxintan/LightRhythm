package com.example.lightrhythm

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lightrhythm.detection.detectBanding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class PhotoDetectionIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun colorSample(): File {
        val width = 960
        val height = 720
        val pixels = IntArray(width * height) { index ->
            val wave = 24 * sin(2 * PI * (index / width) / 190)
            (255 shl 24) or ((128 + wave).toInt() shl 16) or
                ((128 - .299 / .587 * wave).toInt() shl 8) or 128
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("color-detection-", ".png", context.cacheDir)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selectedPhotoIsDetectedAutomaticallyAndCanBeRetried() {
        val file = colorSample()
        val before = file.readBytes()
        try {
            compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
            awaitText("疑似颜色横带")
            compose.onNodeWithText("疑似颜色横带").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("颜色候选周期：", substring = true).assertExists()
            compose.onNodeWithText("基于预览图 960 × 720", substring = true).assertExists()
            compose.onNodeWithText("重新检测").performScrollTo().performClick()
            awaitText("疑似颜色横带")
            compose.onNodeWithText("重新检测").assertIsEnabled()
            compose.onNodeWithContentDescription("所选照片预览").assertExists()
            compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
            compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("修复预览").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("修复预览").assertExists()
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun unreadableSelectionShowsReadErrorAndNoDetectionResult() {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.jpg")
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(missing)) } }
        awaitText("无法读取照片")
        compose.onNodeWithText("选择照片").assertIsEnabled()
        compose.onNodeWithText("检测横纹", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("疑似颜色横带").assertDoesNotExist()
    }

    @Test fun supportedGrayBandsProduceDistinctRepairPreview() {
        val width = 65
        val height = 512
        val pixels = IntArray(width * height) { i ->
            val gray = (128 * (1 + .15 * sin(2 * PI * (i / width) / 16))).toInt()
            (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("repair-preview-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val before = file.readBytes()
            compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
            awaitText("疑似明暗横纹")
            compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
            awaitText("修复预览耗时：")
            compose.onNodeWithContentDescription("修复预览").assertIsDisplayed()
            compose.onNodeWithText("原图", substring = false).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithContentDescription("所选照片预览").assertIsDisplayed()
            compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
            compose.onNodeWithText("保存修复副本").assertExists()
            if (Build.VERSION.SDK_INT >= 29) verifySavedRepair()
            compose.onNodeWithText("修复图", substring = false).performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithContentDescription("修复预览").assertIsDisplayed()
            assertArrayEquals(before, file.readBytes())
        } finally { bitmap.recycle(); file.delete() }
    }

    // Saving while viewing the original must still export the corrected preview.
    private fun verifySavedRepair() {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun ownCopies(): Set<Uri> = buildSet {
            resolver.query(
                collection, arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(context.packageName, "Pictures/LightRhythm/"), null
            )?.use { cursor ->
                while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
        }
        val before = ownCopies()
        try {
            compose.onNodeWithText("保存修复副本").performScrollTo().performClick()
            awaitText("预览副本已保存")
            val exported = (ownCopies() - before).single()
            val bitmap = resolver.openInputStream(exported)?.use { BitmapFactory.decodeStream(it) }
            checkNotNull(bitmap)
            try {
                assertEquals(65, bitmap.width)
                assertEquals(512, bitmap.height)
                val rows = (0 until bitmap.height).map { Color.red(bitmap.getPixel(0, it)) }
                assertTrue("Expected attenuation of the 38-level stripe swing", rows.max() - rows.min() < 12)
            } finally {
                bitmap.recycle()
            }
        } finally {
            (ownCopies() - before).forEach { resolver.delete(it, null, null) }
        }
    }

    @Test fun unsupportedTransparencyKeepsPreviewAndAllowsRetry() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("transparent-detection-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
            awaitText("无法检测这张照片")
            compose.onNodeWithContentDescription("所选照片预览").assertExists()
            compose.onNodeWithText("检测横纹", substring = false).assertIsEnabled()
            compose.onNodeWithText("疑似颜色横带").assertDoesNotExist()
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun newSelectionClearsRepairComparisonControls() {
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val repaired = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val replacement = Bitmap.createBitmap(80, 64, Bitmap.Config.ARGB_8888)
        val current = mutableStateOf(original to (repaired as Bitmap?))
        val shown = mutableStateOf(true)
        try {
            compose.setContent { MaterialTheme {
                if (shown.value) PhotoImportScreen(current.value.first, false, null, {}, repairedPreview = current.value.second)
            } }
            compose.onNodeWithText("原图", substring = false).performScrollTo().performClick()
            compose.runOnIdle { current.value = replacement to null }
            compose.onNodeWithText("修复前后对比").assertDoesNotExist()
            compose.onNodeWithText("保存修复副本").assertDoesNotExist()
            compose.onNodeWithText("保存预览副本").assertExists()
            compose.onNodeWithContentDescription("所选照片预览").assertExists()
        } finally {
            compose.runOnIdle { shown.value = false }; compose.waitForIdle()
            original.recycle(); repaired.recycle(); replacement.recycle()
        }
    }

    @Test fun processingStateDisablesSelectionDetectionRepairAndSave() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff808080.toInt())
        val shown = mutableStateOf(true)
        try {
            compose.setContent {
                MaterialTheme { if (shown.value) PhotoImportScreen(bitmap, false, null, {}, detecting = true) }
            }
            compose.onNodeWithText("正在检测横纹…").assertExists()
            for (label in listOf("选择照片", "检测横纹", "生成修复预览", "保存预览副本")) {
                compose.onNodeWithText(label, substring = false).assertIsNotEnabled()
            }
        } finally { compose.runOnIdle { shown.value = false }; compose.waitForIdle(); bitmap.recycle() }
    }

    @Test fun adapterRunsDetectionOffCallerThreadAndKeepsSourcePixels() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff808080.toInt())
        val caller = Thread.currentThread()
        var actual: Thread? = null
        try {
            val pipeline = PhotoDetectionPipeline { image -> actual = Thread.currentThread(); detectBanding(image) }
            val timed = pipeline.run(bitmap)
            assertNotSame(caller, actual)
            assertEquals(0xff808080.toInt(), bitmap.getPixel(0, 0))
            assertFalse(bitmap.isRecycled)
            assertTrue(timed.elapsedMs >= 0)
        } finally { bitmap.recycle() }
    }
}

/** Feeds a fixture through the same activity-owned state as the picker, without opening system UI. */
@Composable
internal fun PhotoImportWithSample(uri: Uri) {
    val owner = checkNotNull(LocalView.current.findViewTreeViewModelStoreOwner())
    val model = remember(owner) { ViewModelProvider(owner)[PhotoViewModel::class.java] }
    LaunchedEffect(model, uri) { model.select(uri) }
    PhotoImportRoute(model)
}
