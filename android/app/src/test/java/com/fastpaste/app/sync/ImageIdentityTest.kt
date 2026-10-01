package com.fastpaste.app.sync

import com.fastpaste.app.data.ClipboardPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageIdentityTest {
    @Test fun ownClipboardUriPreservesDesktopCaptionAndIdentity() {
        val original = ClipboardPayload(kind = "image", text = "[Hình ảnh 800×600 · abcdef12]",
            mimeType = "image/png", data = "AA/A", thumbnail = "preview")
        val restored = restoreClipboardImage(original.metadataJson().toString(), original.data, original.fingerprint())
        assertEquals(original, restored)
        assertNull(restoreClipboardImage(original.metadataJson().toString(), "BBBB", original.fingerprint()))
    }
}
