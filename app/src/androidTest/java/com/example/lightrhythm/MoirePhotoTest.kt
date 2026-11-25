package com.example.lightrhythm

import android.net.Uri
import android.graphics.ImageDecoder
import android.os.Build
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
    @Test fun photo10ShowsCandidateAndBlocksBandingRepair() = verifyCandidate("10.jpg")
    @Test fun photo12ShowsCandidateAndBlocksBandingRepair() = verifyCandidate("12.jpg")
    @Test fun photos07And08RetainCandidatesAt960() {
        assumeTrue("ImageDecoder requires Android 9 or newer", Build.VERSION.SDK_INT >= 28)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("07.jpg", "08.jpg")) {
            val file = sample(name)
            val before = file.readBytes()
            // Exercise Android's decoder at the smaller size, independently of desktop ImageIO.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.fromFile(file))) { decoder, info, _ ->
                val ratio = 960.0 / maxOf(info.size.width, info.size.height)
                decoder.setTargetSize((info.size.width * ratio).toInt(), (info.size.height * ratio).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try {
                assertEquals(960, maxOf(bitmap.width, bitmap.height))
                assertTrue("Missing candidate for $name at 960", detectMoire(bitmap).isSuspected)
                assertArrayEquals(before, file.readBytes())
            } finally { bitmap.recycle() }
        }
    }
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
        compose.setContent { MaterialTheme { PhotoImportWithSample(Uri.fromFile(file)) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("疑似摩尔纹").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("疑似摩尔纹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生成修复预览").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("疑似摩尔纹暂不支持修复", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertArrayEquals(before, file.readBytes())
    }
}
