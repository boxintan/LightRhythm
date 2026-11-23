package com.example.lightrhythm.detection

import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    for (path in args) {
        val image = ImageIO.read(File(path)) ?: error("Cannot decode $path")
        try {
            val evidence = analyzeBroadColorBanding(image.width, image.height) { y, row ->
                image.getRGB(0, y, image.width, 1, row, 0, image.width)
            }
            println("${File(path).name}: $evidence")
        } finally { image.flush() }
    }
}
