package com.example.lightrhythm

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class LocalBandingPhotoTest {
    @get:Rule val compose = createComposeRule()
    @Test fun window13ShowsNoBandingWarningOrRepairPreview() {
        val directory=InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples",directory != null)
        val file=File(directory!!,"13.jpg")
        val before=file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("检测完成，原图未修改。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似局部横纹").assertDoesNotExist()
        compose.onNodeWithText("疑似明暗横纹").assertDoesNotExist()
        compose.onNodeWithText("疑似摩尔纹").assertDoesNotExist()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("未发现可修复的明确明暗横纹",substring=true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        assertArrayEquals(before,file.readBytes())
    }
    @Test fun photo09ShowsLocalStripesAndRejectsWholeRowRepair() {
        val directory = InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples", directory != null)
        val file = File(directory!!,"09.jpg")
        val before = file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("疑似局部横纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似局部横纹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("亮度候选周期",substring = true).assertDoesNotExist()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("局部或倾斜横纹暂不支持修复",substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        assertArrayEquals(before,file.readBytes())
    }

    @Test fun station19ShowsLocalizedEvidenceAndRejectsWholeRowRepair() {
        val directory=InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples",directory != null)
        val file=File(directory!!,"19.jpg")
        val before=file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("疑似局部横纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似局部横纹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("局部条纹暂不支持修复",substring=true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        assertArrayEquals(before,file.readBytes())
    }

    @Test fun newWindow20ShowsNoWarningAndCannotGenerateRepair() {
        val directory=InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples",directory!=null)
        val file=File(directory!!,"20.jpg")
        val before=file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("检测完成，原图未修改。").fetchSemanticsNodes().isNotEmpty() }
        for(text in listOf("疑似摩尔纹","疑似局部横纹","疑似明暗横纹"))
            compose.onNodeWithText(text).assertDoesNotExist()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("未发现可修复的明确明暗横纹",substring=true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        assertArrayEquals(before,file.readBytes())
    }
}
