package com.example.lightrhythm.detection

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lightrhythm.model.BandingStatus
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Private photos are supplied at runtime and never included in an APK. */
class DetectionTileScaleTest {
    private fun photo(name: String, edge: Int, block: (Bitmap)->Unit) {
        val directory=InstrumentationRegistry.getArguments().getString("detectionSampleDirectory")
        assumeTrue("Provide private photo directory", directory != null)
        val file=File(directory!!,name)
        val before=file.readBytes()
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
            val ratio=minOf(1.0,edge.toDouble()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize((info.size.width*ratio).toInt(),(info.size.height*ratio).toInt())
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            block(bitmap)
            val after=IntArray(pixels.size)
            bitmap.getPixels(after,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            assertArrayEquals(pixels,after)
            assertArrayEquals(before,file.readBytes())
        } finally { bitmap.recycle() }
    }

    @Test fun smallPhoto09KeepsLocalBandsInActualAndroidDecode() = photo("09.jpg",480) { image ->
        val result=detectBanding(image)
        assertEquals(BandingStatus.SUSPECTED_LOCAL_BANDING,result.status)
        assertTrue(result.localBandingEvidence!!.tiles.all { it.size == 64 })
        assertTrue(result.candidateRowOffsets.isEmpty())
        val rows=analyzeImageBanding(image.width,image.height) { y,pixels ->
            image.getPixels(pixels,0,image.width,0,y,image.width,1)
        }
        assertEquals(rows,result)
    }

    @Test fun nativeTilesPreserveChromaEvidenceAtThreePreviewSizes() {
        for (name in listOf("03.jpg","07.jpg","08.jpg","10.jpg","12.jpg")) {
            for (edge in listOf(1600,960,480)) photo(name,edge) { image ->
                val tiles=detectMoire(image)
                val rows=analyzeMoire(image.width,image.height) { y,pixels ->
                    image.getPixels(pixels,0,image.width,0,y,image.width,1)
                }
                assertEquals("$name/$edge",rows,tiles)
                assertTrue("Missing chroma evidence: $name/$edge",tiles.isSuspected)
            }
        }
    }

    @Test fun wideColorBandsDoNotTurnIntoMoireThroughTileReads() = photo("06.jpg",1600) { image ->
        assertFalse(detectMoire(image).isSuspected)
    }
}
