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

class BroadColorPhotoTest {
    @get:Rule val compose = createComposeRule()
    @Test fun photo04IsDetectedWithNeutralSourceConfirmation() = verify("04.jpg")
    @Test fun photo05IsDetectedWithNeutralSourceConfirmation() = verify("05.jpg")
    private fun verify(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples", directory != null)
        val file = File(directory!!, name)
        val before = file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(file).toString()) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("未发现明确的周期横纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("疑似宽幅色带").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似宽幅色带").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("颜色候选周期：", substring = true).assertDoesNotExist()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("宽幅颜色横带暂不支持修复", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("未发现明确的周期横纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似宽幅色带").assertDoesNotExist()
        assertArrayEquals(before, file.readBytes())
    }
}
