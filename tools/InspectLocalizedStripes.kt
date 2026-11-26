package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.example.lightrhythm.model.DetectionConfig



/** Color proposals and accepted region evidence, in source pixels; not a screen recognizer. */
fun main(args: Array<String>) {
    for(path in args) {
        val original=ImageIO.read(File(path)) ?: error("Cannot decode $path")
        try {
            for(edge in listOf(0,1600,960)) {
                val scale=if(edge==0) 1.0 else minOf(1.0,edge.toDouble()/maxOf(original.width,original.height))
                if(edge!=0 && scale==1.0) continue
                val image=BufferedImage((original.width*scale).toInt(),(original.height*scale).toInt(),BufferedImage.TYPE_INT_RGB)
                try {
                    image.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(original,0,0,image.width,image.height,null); dispose()
                    }
                    val reader: PixelTileReader = { x,y,w,h,pixels -> image.getRGB(x,y,w,h,pixels,0,w) }
                    println("${File(path).name} ${image.width}x${image.height}")
                    println("proposals="+findColorRectangles(image.width,image.height,reader))
                    println("accepted="+analyzeLocalizedStripes(image.width,image.height,DetectionConfig(),reader))
                } finally { image.flush() }
            }
        } finally { original.flush() }
    }
}
