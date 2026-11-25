package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/** Desktop mechanism check, not a phone benchmark. Times exclude decode and scaling. */
fun main(args: Array<String>) {
    println("file,width,height,rowCalls,tileCalls,rowPixels,tilePixels,rowMedianMs,tileMedianMs")
    for (path in args) {
        val original = ImageIO.read(File(path)) ?: error("Cannot decode $path")
        val ratio = minOf(1.0,1600.0/maxOf(original.width,original.height))
        val image = BufferedImage((original.width*ratio).toInt(),(original.height*ratio).toInt(),BufferedImage.TYPE_INT_RGB)
        try {
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original,0,0,image.width,image.height,null)
                dispose()
            }
            var rowCalls = 0; var rowPixels = 0L; var tileCalls = 0; var tilePixels = 0L
            val rowReader: (Int,IntArray)->Unit = { y, pixels ->
                rowCalls++; rowPixels += pixels.size
                image.getRGB(0,y,image.width,1,pixels,0,image.width)
            }
            val tileReader: PixelTileReader = { left,top,width,height,pixels ->
                tileCalls++; tilePixels += pixels.size
                image.getRGB(left,top,width,height,pixels,0,width)
            }
            fun rows() = analyzeMoire(image.width,image.height,rowReader)
            fun tiles() = analyzeMoireTiles(image.width,image.height,tileReader)
            repeat(3) { check(rows() == tiles()) { "Tile reader changed evidence for $path" } }
            rowCalls=0; rowPixels=0; tileCalls=0; tilePixels=0
            val rowTimes = mutableListOf<Double>(); val tileTimes = mutableListOf<Double>()
            fun timed(out: MutableList<Double>, block: ()->MoireResult): MoireResult {
                val start=System.nanoTime(); val result=block()
                out += (System.nanoTime()-start)/1e6
                return result
            }
            repeat(6) { iteration ->
                if (iteration%2 == 0) check(timed(rowTimes,::rows) == timed(tileTimes,::tiles))
                else check(timed(tileTimes,::tiles) == timed(rowTimes,::rows))
            }
            fun median(values: List<Double>): String {
                val sorted=values.sorted()
                return String.format(Locale.ROOT,"%.3f",(sorted[2]+sorted[3])/2)
            }
            println("${File(path).name},${image.width},${image.height},${rowCalls/6},${tileCalls/6},${rowPixels/6},${tilePixels/6},${median(rowTimes)},${median(tileTimes)}")
        } finally { image.flush(); original.flush() }
    }
}
