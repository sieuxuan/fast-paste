package com.fastpaste.app.sync

internal fun thumbnailSampleSize(width: Int, height: Int, edge: Int = 256): Int {
    var sample = 1
    while (maxOf(width, height).toLong() / (sample.toLong() * 2) >= edge) sample *= 2
    return sample
}
