package com.fastpaste.app.sync

import android.content.ClipData
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.text.Spanned
import androidx.core.content.FileProvider
import com.fastpaste.app.data.ClipboardFilePayload
import com.fastpaste.app.data.ClipboardPayload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object AndroidClipboardCodec {
    fun read(context: Context, clip: ClipData?): ClipboardPayload? {
        if (clip == null || clip.itemCount == 0) return null

        val uris = buildList {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(::add)
            }
        }
        if (uris.isNotEmpty()) return readUris(context, uris)

        val item = clip.getItemAt(0)
        val html = item.htmlText.orEmpty().ifBlank {
            val styled = item.text
            if (styled is Spanned) {
                Html.toHtml(styled, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
            } else {
                ""
            }
        }
        val text = item.text?.toString()
            ?: item.coerceToText(context)?.toString()
            ?: return null
        if (text.isEmpty()) return null
        return if (html.isNotBlank()) {
            ClipboardPayload(
                kind = ClipboardPayload.KIND_HTML,
                text = text,
                html = html,
                mimeType = "text/html"
            )
        } else {
            ClipboardPayload.text(text)
        }
    }

    fun write(context: Context, payload: ClipboardPayload): ClipData {
        return when (payload.kind) {
            ClipboardPayload.KIND_HTML -> ClipData.newHtmlText(
                "Fast Paste",
                payload.text,
                payload.html.ifBlank { payload.text }
            )

            ClipboardPayload.KIND_IMAGE,
            ClipboardPayload.KIND_FILES -> writeFiles(context, payload)

            else -> ClipData.newPlainText("Fast Paste", payload.text)
        }
    }

    private fun readUris(context: Context, uris: List<Uri>): ClipboardPayload? {
        val resolver = context.contentResolver
        var totalBytes = 0
        val files = mutableListOf<ClipboardFilePayload>()
        for (uri in uris.take(MAX_FILES)) {
            val bytes = resolver.openInputStream(uri)?.use { input ->
                readLimited(input, ClipboardPayload.MAX_PAYLOAD_BYTES - totalBytes)
            } ?: continue
            totalBytes += bytes.size
            if (totalBytes > ClipboardPayload.MAX_PAYLOAD_BYTES) return null
            val mime = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
            files += ClipboardFilePayload(
                name = displayName(context, uri),
                mime = mime,
                data = ClipboardPayload.encode(bytes)
            )
        }
        if (files.isEmpty()) return null

        if (files.size == 1 && files[0].mime.startsWith("image/")) {
            val file = files[0]
            val hash = sha256(ClipboardPayload.decode(file.data)).take(8)
            return ClipboardPayload(
                kind = ClipboardPayload.KIND_IMAGE,
                text = "[Hình ảnh · $hash]",
                mimeType = file.mime,
                data = file.data
            )
        }

        val names = files.joinToString(", ") { it.name }
        val digestInput = files.joinToString("\u0000") { "${it.name}\u0000${it.data}" }
        val hash = sha256(digestInput.toByteArray()).take(8)
        return ClipboardPayload(
            kind = ClipboardPayload.KIND_FILES,
            text = "[${files.size} tệp · $names · $hash]",
            mimeType = "application/octet-stream",
            files = files
        )
    }

    private fun writeFiles(context: Context, payload: ClipboardPayload): ClipData {
        val files = if (payload.kind == ClipboardPayload.KIND_IMAGE) {
            listOf(
                ClipboardFilePayload(
                    name = "clipboard-image.${extensionForMime(payload.mimeType)}",
                    mime = payload.mimeType.ifBlank { "image/png" },
                    data = payload.data
                )
            )
        } else {
            payload.files
        }
        require(files.isNotEmpty()) { "Clipboard không có dữ liệu file." }

        val root = File(context.cacheDir, "clipboard").also { it.mkdirs() }
        cleanupOldClipboardFiles(root)
        val folder = File(root, payload.fingerprint())
        folder.mkdirs()
        val usedNames = mutableSetOf<String>()
        val uris = files.mapIndexed { index, item ->
            var fileName = sanitizeFileName(item.name, index)
            if (!usedNames.add(fileName.lowercase())) {
                fileName = "$index-$fileName"
                usedNames.add(fileName.lowercase())
            }
            val target = File(folder, fileName)
            target.writeBytes(ClipboardPayload.decode(item.data))
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
        }

        return ClipData.newUri(context.contentResolver, "Fast Paste", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
    }

    private fun readLimited(input: InputStream, remaining: Int): ByteArray? {
        if (remaining <= 0) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > remaining) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun displayName(context: Context, uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            val column = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (column >= 0 && cursor?.moveToFirst() == true) {
                cursor.getString(column).orEmpty().ifBlank { "clipboard-file" }
            } else {
                uri.lastPathSegment.orEmpty().ifBlank { "clipboard-file" }
            }
        } finally {
            cursor?.close()
        }
    }

    private fun sanitizeFileName(name: String, index: Int): String {
        val clean = name.replace(Regex("[<>:\"/\\\\|?*]"), "_").take(180)
        return clean.ifBlank { "clipboard-file-$index" }
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun cleanupOldClipboardFiles(root: File) {
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHE_FOLDERS)
            ?.forEach { it.deleteRecursively() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_FILES = 16
    private const val MAX_CACHE_FOLDERS = 50
}
