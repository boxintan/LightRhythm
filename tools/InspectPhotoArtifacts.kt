package com.example.lightrhythm.detection

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Development diagnostics only: resizing variants are not independent validation samples. */
fun main(args: Array<String>) {
    println("file,width,height,banding,periodRows,moire,moireCells,localCells,localSize,localPeriodRows")
    for (path in args) {
        val original = ImageIO.read(File(path)) ?: error("Cannot decode $path")
        try {
            val sizes = mutableSetOf<Pair<Int,Int>>()
            for (edge in listOf(0, 1600, 960, 480)) {
                val ratio = if (edge == 0) 1.0 else minOf(1.0, edge.toDouble() / maxOf(original.width, original.height))
                val dimensions = (original.width * ratio).toInt() to (original.height * ratio).toInt()
                if (!sizes.add(dimensions)) continue
                val image = BufferedImage(dimensions.first, dimensions.second, BufferedImage.TYPE_INT_RGB)
                try {
                    image.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(original, 0, 0, image.width, image.height, null)
                        dispose()
                    }
                    val reader: (Int, IntArray) -> Unit = { y, row -> image.getRGB(0, y, image.width, 1, row, 0, image.width) }
                    val tileReader: PixelTileReader = { left, top, width, height, pixels ->
                        image.getRGB(left, top, width, height, pixels, 0, width)
                    }
                    val banding = analyzeImageBanding(image.width, image.height, readTile = tileReader, readRow = reader)
                    val moire = analyzeMoireTiles(image.width, image.height, tileReader)
                    val local = banding.localBandingEvidence
                    println("${File(path).name},${image.width},${image.height},${banding.status},${banding.candidatePeriodRows},${moire.isSuspected},${moire.tiles.size},${local?.tiles?.size ?: 0},${local?.tiles?.firstOrNull()?.size ?: ""},${local?.tiles?.map { it.periodRows }?.sorted()?.joinToString(";") ?: ""}")
                } finally { image.flush() }
            }
        } finally { original.flush() }
    }
}
