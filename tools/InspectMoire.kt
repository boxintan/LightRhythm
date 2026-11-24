package com.example.lightrhythm.detection

import java.io.File
import javax.imageio.ImageIO
import java.awt.RenderingHints
import java.awt.image.BufferedImage

fun main(args: Array<String>) {
    for (path in args) {
        val original = ImageIO.read(File(path)) ?: error("Cannot decode $path")
        for (edge in listOf(0, 1600, 960, 480)) {
            val scale = if (edge == 0) 1.0 else minOf(1.0, edge.toDouble()/maxOf(original.width, original.height))
            val image = BufferedImage((original.width*scale).toInt(), (original.height*scale).toInt(), BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original, 0, 0, image.width, image.height, null); dispose()
            }
            val start = System.nanoTime()
            val result = analyzeMoire(image.width, image.height) { y, row -> image.getRGB(0, y, image.width, 1, row, 0, image.width) }
            println("${File(path).name} ${image.width}x${image.height}: suspected=${result.isSuspected} tiles=${result.tiles.size} ms=${(System.nanoTime()-start)/1000000}")
            println(result.tiles.joinToString { "${it.size}px:${it.channel}:${it.dx},${it.dy}/${"%.1f".format(java.util.Locale.ROOT,it.recurrenceDisplacementPixels)}" })
            image.flush()
        }
        original.flush()
    }
}
