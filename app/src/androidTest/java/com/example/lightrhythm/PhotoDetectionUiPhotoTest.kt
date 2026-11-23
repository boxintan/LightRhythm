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

/** Checks the route after a selected URI is supplied; the system picker itself is unchanged. */
class PhotoDetectionUiPhotoTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstClassroomPhotoShowsDetectionAndPreviewCoordinates() = verify("01.jpg", 316)
    @Test fun secondClassroomPhotoShowsDetectionAndPreviewCoordinates() = verify("02.jpg", 385)

    private fun verify(name: String, period: Int) {
        val directory = InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide detectionSampleDirectory for private-photo UI regressions", directory != null)
        val file = File(directory!!, name)
        val before = file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("疑似颜色横带").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似颜色横带").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("颜色候选周期：$period 像素行").assertExists()
        compose.onNodeWithText("基于预览图 1600 × 1200", substring = true).assertExists()
        compose.onNodeWithContentDescription("所选照片预览").assertIsDisplayed()
        compose.onNodeWithContentDescription("修复预览").assertDoesNotExist()
        assertArrayEquals(before, file.readBytes())
    }
}
