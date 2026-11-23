package com.example.lightrhythm

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lightrhythm.detection.detectMoire
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Private fixtures supplied with -e detectionSampleDirectory; never packaged in the APK. */
class MoirePhotoTest {
    @get:Rule val compose = createComposeRule()
    @Test fun photo07ShowsCandidateAndBlocksBandingRepair() = verifyCandidate("07.jpg")
    @Test fun photo08ShowsCandidateAndBlocksBandingRepair() = verifyCandidate("08.jpg")
    @Test fun photo06DoesNotBecomeAMoireCandidate() {
        val file = sample("06.jpg")
        val bitmap = decodePhotoPreview(InstrumentationRegistry.getInstrumentation().targetContext, Uri.fromFile(file))
        try { assertFalse(detectMoire(bitmap).isSuspected) } finally { bitmap.recycle() }
    }
    private fun sample(name: String): File {
        val directory = InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private samples", directory != null)
        return File(directory!!, name)
    }
    private fun verifyCandidate(name: String) {
        val file = sample(name)
        val before = file.readBytes()
        compose.setContent { MaterialTheme { PhotoImportRoute(initialPhotoUri = Uri.fromFile(file).toString()) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("疑似摩尔纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似摩尔纹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("疑似摩尔纹暂不支持修复", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(before, file.readBytes())
    }
}
