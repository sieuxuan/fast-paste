package com.fastpaste.app.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class ClipboardFilePayload(
    val name: String,
    val mime: String = "application/octet-stream",
    val data: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("mime", mime)
        .put("data", data)
}

data class ClipboardPayload(
    val kind: String = KIND_TEXT,
    val text: String,
    val html: String = "",
    val mimeType: String = "text/plain",
    val data: String = "",
    val files: List<ClipboardFilePayload> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("text", text)
        .put("html", html)
        .put("mimeType", mimeType)
        .put("data", data)
        .put("files", JSONArray().also { array -> files.forEach { array.put(it.toJson()) } })

    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toJson().toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun protocolJson(): String = JSONObject()
        .put("app", "fastpaste")
        .put("type", "clipboard_payload")
        .put("payload", toJson())
        .toString()

    fun filesJson(): String = JSONArray().also { array ->
        files.forEach { array.put(it.toJson()) }
    }.toString()

    fun isWithinLimit(): Boolean {
        fun decodedSize(value: String): Long {
            val padding = when {
                value.endsWith("==") -> 2L
                value.endsWith("=") -> 1L
                else -> 0L
            }
            return (value.length.toLong() / 4L * 3L - padding).coerceAtLeast(0L)
        }
        val totalBytes = decodedSize(data) + files.sumOf { decodedSize(it.data) }
        return files.size <= 16 && totalBytes <= MAX_PAYLOAD_BYTES
    }

    companion object {
        const val KIND_TEXT = "text"
        const val KIND_HTML = "html"
        const val KIND_IMAGE = "image"
        const val KIND_FILES = "files"
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

        fun text(value: String) = ClipboardPayload(text = value)

        fun fromJson(json: JSONObject): ClipboardPayload {
            val filesJson = json.optJSONArray("files") ?: JSONArray()
            val files = buildList {
                for (index in 0 until minOf(filesJson.length(), 16)) {
                    val item = filesJson.optJSONObject(index) ?: continue
                    add(
                        ClipboardFilePayload(
                            name = item.optString("name", "clipboard-file"),
                            mime = item.optString("mime", "application/octet-stream"),
                            data = item.optString("data")
                        )
                    )
                }
            }
            return ClipboardPayload(
                kind = json.optString("kind", KIND_TEXT),
                text = json.optString("text"),
                html = json.optString("html"),
                mimeType = json.optString("mimeType", json.optString("mime_type", "text/plain")),
                data = json.optString("data"),
                files = files
            )
        }

        fun fromEntry(entry: ClipboardEntry): ClipboardPayload {
            val files = runCatching { JSONArray(entry.filesJson) }.getOrDefault(JSONArray())
            return fromJson(
                JSONObject()
                    .put("kind", entry.payloadType)
                    .put("text", entry.content)
                    .put("html", entry.htmlContent)
                    .put("mimeType", entry.mimeType)
                    .put("data", entry.payloadData)
                    .put("files", files)
            )
        }

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(data: String): ByteArray = Base64.decode(data, Base64.DEFAULT)
    }
}
