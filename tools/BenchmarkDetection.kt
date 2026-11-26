package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import com.example.lightrhythm.model.DetectionConfig

/** Stage timing on desktop, not a substitute for the Android pipeline budget. */
fun main(args: Array<String>) {
    println("file,iteration,width,height,globalMs,localMs,regionMs,moireMs,totalMs")
    for(path in args) {
        val original=ImageIO.read(File(path)) ?: error("Cannot read $path")
        val ratio=minOf(1.0,1600.0/maxOf(original.width,original.height))
        val image=BufferedImage((original.width*ratio).toInt(),(original.height*ratio).toInt(),BufferedImage.TYPE_INT_RGB)
        try {
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original,0,0,image.width,image.height,null); dispose()
            }
            val reader: PixelTileReader={ x,y,w,h,out -> image.getRGB(x,y,w,h,out,0,w) }
            repeat(3) { iteration ->
                val start=System.nanoTime()
                val global=analyzeDetectionProfiles(readDetectionProfiles(image.width,image.height) { y,out -> image.getRGB(0,y,image.width,1,out,0,image.width) })
                val a=System.nanoTime()
                val local=if(!global.isSuspected) analyzeLocalBandingTiles(image.width,image.height,DetectionConfig(),reader) else null
                val b=System.nanoTime()
                if(!global.isSuspected && local==null) analyzeLocalizedStripes(image.width,image.height,DetectionConfig(),reader)
                val c=System.nanoTime()
                analyzeMoireTiles(image.width,image.height,reader)
                val d=System.nanoTime()
                println(listOf(File(path).name,iteration,image.width,image.height,(a-start)/1e6,(b-a)/1e6,(c-b)/1e6,(d-c)/1e6,(d-start)/1e6).joinToString(","))
            }
        } finally { image.flush(); original.flush() }
    }
}
