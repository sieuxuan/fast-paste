package com.fastpaste.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPayloadTest {
    @Test
    fun inlineRichTextHasBodyWhilePendingImageDoesNot() {
        val html = ClipboardPayload(kind = "html", text = "hello", html = "<b>hello</b>")
        assertTrue(html.hasBody())
        assertTrue(ClipboardPayload.fromJson(html.metadataJson()).hasBody())
        assertFalse(ClipboardPayload(kind = "image", text = "image").hasBody())
    }
    @Test
    fun fingerprintMatchesDesktopWithSlashesUnicodeAndControlCharacters() {
        val payload = ClipboardPayload(kind = "image", text = "ảnh / 🙂\n\u0001",
            mimeType = "image/png", data = "AA/A")
        assertEquals("3787d0ba5fa60929472e14a743397613c4ebc60d2c359859270a2858b463f961", payload.fingerprint())
        assertTrue(payload.matchesFingerprint("91cf4f3c682861451c4924f3a28415af1a41ccfadbfd2b2081d25a8530960a97"))
        assertFalse(payload.matchesFingerprint("invalid"))
    }
    @Test
    fun legacyFilePayloadIsIgnoredAndNotReserialized() {
        val legacy = JSONObject()
            .put("kind", "files")
            .put("text", "[1 tệp · old.txt]")
            .put("files", JSONArray().put(
                JSONObject()
                    .put("name", "old.txt")
                    .put("mime", "text/plain")
                    .put("data", "QUJDRA==")
            ))

        val payload = ClipboardPayload.fromJson(legacy)

        assertEquals(0L, payload.encodedSize())
        assertFalse(payload.toJson().has("files"))
        assertEquals("[1 tệp · old.txt]", payload.text)
    }
}
