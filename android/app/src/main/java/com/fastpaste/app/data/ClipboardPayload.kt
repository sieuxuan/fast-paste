package com.fastpaste.app.data

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest

data class ClipboardPayload(
    val kind: String = KIND_TEXT,
    val text: String,
    val html: String = "",
    val mimeType: String = "text/plain",
    val data: String = "",
    val thumbnail: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("text", text)
        .put("html", html)
        .put("mimeType", mimeType)
        .put("data", data)
        .put("thumbnail", thumbnail)

    fun metadataJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("text", text)
        .put("html", html)
        .put("mimeType", mimeType)
        .put("data", "")
        .put("thumbnail", thumbnail)

    fun fingerprint(): String {
        val identity = JSONObject()
            .put("kind", kind)
            .put("text", text)
            .put("html", html)
            .put("mimeType", mimeType)
            .put("data", data)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun protocolJson(): String = JSONObject()
        .put("app", "fastpaste")
        .put("type", "clipboard_payload")
        .put("payload", toJson())
        .toString()

    fun isWithinLimit(): Boolean =
        encodedSize() <= MAX_PAYLOAD_BYTES && thumbnail.length <= MAX_THUMBNAIL_CHARS

    fun encodedSize(): Long {
        fun decodedSize(value: String): Long {
            val padding = when {
                value.endsWith("==") -> 2L
                value.endsWith("=") -> 1L
                else -> 0L
            }
            return (value.length.toLong() / 4L * 3L - padding).coerceAtLeast(0L)
        }
        return decodedSize(data)
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_HTML = "html"
        const val KIND_IMAGE = "image"
        const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
        const val MAX_THUMBNAIL_CHARS = 512 * 1024

        fun text(value: String) = ClipboardPayload(text = value)

        fun fromJson(json: JSONObject): ClipboardPayload {
            return ClipboardPayload(
                kind = json.optString("kind", KIND_TEXT),
                text = json.optString("text"),
                html = json.optString("html"),
                mimeType = json.optString("mimeType", json.optString("mime_type", "text/plain")),
                data = json.optString("data"),
                thumbnail = json.optString("thumbnail").take(MAX_THUMBNAIL_CHARS)
            )
        }

        fun fromEntry(entry: ClipboardEntry): ClipboardPayload {
            return fromJson(
                JSONObject()
                    .put("kind", entry.payloadType)
                    .put("text", entry.content)
                    .put("html", entry.htmlContent)
                    .put("mimeType", entry.mimeType)
                    .put("data", entry.payloadData)
                    .put("thumbnail", entry.thumbnail)
            )
        }

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)
    }
}
