package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Controlled nuisance families, not real-scene validation or a model of every blind. */
class NeutralTextureEvaluationTest {
    @Test fun jpegAndPreviewResamplingOfTintedNeutralTexturesDoNotCreateAnAlert() {
        for(period in listOf(11.5,19.0,31.5)) {
            val source=BufferedImage(432,768,BufferedImage.TYPE_INT_RGB)
            for(y in 0 until source.height) for(x in 0 until source.width) {
                val light=140+58*sin(2*PI*(y-.07*x)/period)+5.0*x/source.width
                val r=(light+40).roundToInt(); val g=(light+12).roundToInt(); val b=light.roundToInt()
                source.setRGB(x,y,(r shl 16) or (g shl 8) or b)
            }
            try {
                val bytes=ByteArrayOutputStream().also { assertTrue(ImageIO.write(source,"jpg",it)) }.toByteArray()
                val jpeg=ImageIO.read(ByteArrayInputStream(bytes))
                try {
                    for(scale in listOf(1.0,5.0/6)) {
                        val preview=BufferedImage((jpeg.width*scale).toInt(),(jpeg.height*scale).toInt(),BufferedImage.TYPE_INT_RGB)
                        try {
                            preview.createGraphics().apply {
                                setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                drawImage(jpeg,0,0,preview.width,preview.height,null); dispose()
                            }
                            val result=analyzeMoireTiles(preview.width,preview.height) { x,y,w,h,out ->
                                preview.getRGB(x,y,w,h,out,0,w)
                            }
                            assertTrue(result.testedTiles>0)
                            assertFalse("period=$period scale=$scale",result.isSuspected)
                        } finally { preview.flush() }
                    }
                } finally { jpeg.flush() }
            } finally { source.flush() }
        }
    }
}
