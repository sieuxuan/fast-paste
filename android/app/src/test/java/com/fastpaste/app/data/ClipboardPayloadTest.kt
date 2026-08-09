package com.fastpaste.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClipboardPayloadTest {
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
