package com.example.lightrhythm

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRotationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            find(node.getChild(i), match)?.let { return it }
        }
        return null
    }

    private fun awaitNode(
        scrollControls: Boolean = false,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8000
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val node = find(root, match)
            if (node != null && node.isVisibleToUser) return node
            if (scrollControls) {
                find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(if (scrollControls) 250 else 80)
        } while (SystemClock.uptimeMillis() < end)
        throw AssertionError("Expected a visible node on the photo screen")
    }

    @Test fun rotatingKeepsPreviewAndFeedbackReachable() {
        val file = File.createTempFile("rotation", ".png", context.cacheDir)
        val fixture = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        fixture.eraseColor(android.graphics.Color.GRAY)
        try {
            file.outputStream().use { assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val model = ViewModelProvider(it)[PhotoViewModel::class.java]
                    model.select(Uri.fromFile(file))
                    assertTrue(model.loading)
                }
                scenario.recreate()
                awaitNode { it.contentDescription == "所选照片预览" }
                awaitNode(scrollControls = true) { it.text?.toString() == "检测完成，原图未修改。" }
                lateinit var original: Bitmap
                scenario.onActivity {
                    val model = ViewModelProvider(it)[PhotoViewModel::class.java]
                    original = model.bitmap!!
                    model.process()
                }
                scenario.recreate()
                for (landscape in listOf(true, false)) {
                    scenario.onActivity {
                        it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    val preview = awaitNode {
                        val rotated = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        rotated == landscape && it.contentDescription == "所选照片预览"
                    }
                    val image = Rect().also(preview::getBoundsInScreen)
                    assertTrue(image.width() > 0 && image.height() > 0)
                    if (landscape) {
                        val select = awaitNode { it.text?.toString() == "选择照片" }
                        assertTrue(Rect().also(select::getBoundsInScreen).right <= image.left)
                    }
                    awaitNode(scrollControls = true) {
                        it.text?.toString() == "未发现可修复的明确明暗横纹，未生成修复预览。"
                    }
                    awaitNode { it.contentDescription == "所选照片预览" }
                    scenario.onActivity { assertSame(original, ViewModelProvider(it)[PhotoViewModel::class.java].bitmap) }
                }
            }
        } finally {
            fixture.recycle()
            file.delete()
        }
    }
}
