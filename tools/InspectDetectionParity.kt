package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Hash complete public results, including every numerical profile/evidence value; no timings. */
fun main(args: Array<String>) {
    println("file,width,height,resultSha256")
    for(path in args) {
        val source=ImageIO.read(File(path)) ?: error("Cannot decode $path")
        try {
            val dimensions=mutableSetOf<Pair<Int,Int>>()
            for(edge in listOf(0,1600,960,480)) {
                val scale=if(edge==0) 1.0 else minOf(1.0,edge.toDouble()/maxOf(source.width,source.height))
                val width=(source.width*scale).toInt(); val height=(source.height*scale).toInt()
                if(!dimensions.add(width to height)) continue
                val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
                try {
                    image.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(source,0,0,width,height,null); dispose()
                    }
                    val reader: PixelTileReader={ x,y,w,h,out -> image.getRGB(x,y,w,h,out,0,w) }
                    val banding=analyzeImageBanding(width,height,readTile=reader) { y,out -> image.getRGB(0,y,width,1,out,0,width) }
                    val moire=analyzeMoireTiles(width,height,reader)
                    val digest=MessageDigest.getInstance("SHA-256")
                    digest.update(banding.toString().toByteArray(Charsets.UTF_8))
                    digest.update(moire.toString().toByteArray(Charsets.UTF_8))
                    val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    println("${File(path).name},$width,$height,$hash")
                } finally { image.flush() }
            }
        } finally { source.flush() }
    }
}
