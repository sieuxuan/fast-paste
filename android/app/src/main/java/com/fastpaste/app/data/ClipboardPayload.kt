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
    fun hasBody(): Boolean = when (kind) {
        KIND_TEXT -> true
        KIND_HTML -> html.isNotEmpty()
        else -> data.isNotEmpty()
    }

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
        return hashIdentity(identityJson())
    }

    fun matchesFingerprint(expected: String): Boolean =
        fingerprint() == expected || hashIdentity(identityJson().replace("/", "\\/")) == expected

    // Match serde_json's field order and escaping. Android JSONObject escapes
    // '/', and the JVM test implementation can reorder fields; neither is a
    // portable representation for a cryptographic identity.
    private fun identityJson(): String = buildString {
        append('{')
        listOf("kind" to kind, "text" to text, "html" to html, "mimeType" to mimeType, "data" to data)
            .forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append('"').append(key).append("\":\"")
                value.forEach { character ->
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (character < ' ') {
                            append("\\u").append(character.code.toString(16).padStart(4, '0'))
                        } else append(character)
                    }
                }
                append('"')
            }
        append('}')
    }

    private fun hashIdentity(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
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
