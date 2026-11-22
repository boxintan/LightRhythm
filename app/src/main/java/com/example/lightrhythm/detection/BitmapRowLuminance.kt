package com.example.lightrhythm.detection

import android.graphics.Bitmap

/**
 * A 模块第一步：读取真实 Bitmap，输出每行的平均亮度近似值，不改动输入像素。
 * 输入应已经应用 EXIF 方向；返回值使用此 Bitmap 的坐标，不自动对应原图或缩略图。
 * 需要可读取像素的软件 Bitmap（解码时可使用 ALLOCATOR_SOFTWARE）。
 */
fun rowLuminance(image: Bitmap): DoubleArray {
    require(!image.isRecycled) { "图片已经被释放" }
    require(image.config != Bitmap.Config.HARDWARE) { "请使用可读取像素的软件 Bitmap" }
    return computeRowLuminance(image.width, image.height) { y, pixels ->
        image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)
    }
}
