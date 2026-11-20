package com.example.lightrhythm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoSaverTest {
    @Test
    fun savesDistinctReadablePublishedCopies() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        val created = mutableListOf<Uri>()
        try {
            val first = savePhotoPreview(context, bitmap).also { created.add(it) }
            val second = savePhotoPreview(context, bitmap).also { created.add(it) }
            assertNotEquals(first, second)
            for (uri in created) {
                val restored = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }!!
                try {
                    assertEquals(32, restored.width)
                    assertEquals(24, restored.height)
                    assertEquals(Color.MAGENTA, restored.getPixel(0, 0))
                } finally { restored.recycle() }
                resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertEquals("Pictures/LightRhythm/", it.getString(1))
                }
            }
            assertFalse(bitmap.isRecycled)
            assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0))
        } finally {
            created.forEach { resolver.delete(it, null, null) }
            bitmap.recycle()
        }
    }
}
