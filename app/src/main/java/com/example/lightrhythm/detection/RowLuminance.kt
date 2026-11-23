package com.example.lightrhythm.detection

/**
 * 每行一个平均亮度近似值，范围 0..255；下标与输入图的行坐标一致。
 * 这里计算的是编码 RGB 的加权值，不是线性光照强度，也不是横纹检测结论。
 * 通过按行读取像素，避免额外复制整张图片；应在后台线程调用。
 */
internal fun computeRowLuminance(
    width: Int,
    height: Int,
    readRow: (y: Int, pixels: IntArray) -> Unit,
): DoubleArray {
    require(width > 0 && height > 0) { "图片宽高必须大于零" }
    val pixels = IntArray(width)
    return DoubleArray(height) { y ->
        readRow(y, pixels)
        var total = 0.0
        for (pixel in pixels) {
            total += pixelLuminance(pixel)
        }
        total / width
    }
}

internal fun pixelLuminance(pixel: Int): Double {
    require((pixel ushr 24) == 255) { "请先将透明图片合成为不透明图片" }
    val red = (pixel ushr 16) and 255
    val green = (pixel ushr 8) and 255
    val blue = pixel and 255
    return 0.299 * red + 0.587 * green + 0.114 * blue
}
