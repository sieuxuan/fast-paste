package com.fastpaste.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {
    @Test fun photographsDecodeOnlyEnoughPixelsForPreview() {
        assertEquals(16, thumbnailSampleSize(6000, 4000))
        assertEquals(16, thumbnailSampleSize(4000, 6000))
        assertEquals(1, thumbnailSampleSize(128, 64))
    }
}
