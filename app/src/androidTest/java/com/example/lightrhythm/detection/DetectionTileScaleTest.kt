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

    @Test fun window13DoesNotTriggerBandingAtThreeAndroidDecodeSizes() {
        for(edge in listOf(1280,960,480)) photo("13.jpg",edge) { image ->
            val result=detectBanding(image)
            assertFalse("Window false positive at $edge",result.isSuspected)
            assertNull(result.localBandingEvidence)
            assertTrue(result.candidateRowOffsets.isEmpty())
            assertFalse(detectMoire(image).isSuspected)
        }
    }

    @Test fun newlyLabeledScreenSamplesAreDetectedAtTheAppPreviewSize() {
        for(name in listOf("14.jpg","17.jpg","18.jpg","19.jpg")) photo(name,1600) { image ->
            val result=detectBanding(image)
            assertEquals(name,BandingStatus.SUSPECTED_LOCAL_BANDING,result.status)
            assertTrue(name,result.candidateRowOffsets.isEmpty())
            if(name=="19.jpg") {
                val regions=result.localizedStripes
                assertTrue("Station display evidence missing",regions.size>=2)
                assertTrue("Evidence should be on the upper displays",regions.all {
                    it.axis==com.example.lightrhythm.model.StripeAxis.HORIZONTAL &&
                        it.y>=image.height*.15 && it.y+it.height<=image.height*.35
                })
            }
        }
    }

    @Test fun stationCeilingReflectionIsNotAHorizontalBandOrMoireCandidate() = photo("19.jpg",1600) { image ->
        // Floor above the reflected displays: includes real ceiling slats and the central column.
        val crop=Bitmap.createBitmap(image,image.width/4,image.height/2,image.width/2,image.height/6)
        try {
            assertFalse(detectBanding(crop).isSuspected)
            assertFalse(detectMoire(crop).isSuspected)
        } finally { crop.recycle() }
    }

    @Test fun reportAllNewSamplesWithoutTreatingCurrentOutputsAsLabels() {
        for(name in (14..19).map { "$it.jpg" }) photo(name,1600) { image ->
            val result=detectBanding(image)
            val moire=detectMoire(image)
            android.util.Log.i("NewSampleDetection","$name ${image.width}x${image.height} " +
                "${result.status} moire=${moire.isSuspected} regions=${result.localizedStripes}")
        }
    }

    @Test fun newWindow20DoesNotTriggerAtNativeAndThreePreviewSizes() {
        for(edge in listOf(1920,1600,960,480)) photo("20.jpg",edge) { image ->
            assertFalse("Banding false positive at $edge",detectBanding(image).isSuspected)
            assertFalse("Moire false positive at $edge",detectMoire(image).isSuspected)
        }
    }
}
