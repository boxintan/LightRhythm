package com.example.lightrhythm

import android.graphics.Bitmap
import android.net.Uri
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
            compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(file).toString()) } }
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
            awaitText("颜色横带暂不支持修复")
            compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
            assertArrayEquals(before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun unreadableSelectionShowsReadErrorAndNoDetectionResult() {
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}.jpg")
        compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(missing).toString()) } }
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
            compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(file).toString()) } }
            awaitText("疑似明暗横纹")
            compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
            awaitText("修复预览耗时：")
            compose.onNodeWithContentDescription("修复预览").performScrollTo().assertIsDisplayed()
            assertArrayEquals(before, file.readBytes())
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun unsupportedTransparencyKeepsPreviewAndAllowsRetry() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("transparent-detection-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(file).toString()) } }
            awaitText("无法检测这张照片")
            compose.onNodeWithContentDescription("所选照片预览").assertExists()
            compose.onNodeWithText("检测横纹", substring = false).assertIsEnabled()
            compose.onNodeWithText("疑似颜色横带").assertDoesNotExist()
        } finally { bitmap.recycle(); file.delete() }
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
