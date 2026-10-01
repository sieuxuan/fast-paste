package com.fastpaste.app.sync

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.text.Spanned
import androidx.core.content.FileProvider
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

            ClipboardPayload.KIND_IMAGE -> writeImage(context, payload)

            else -> ClipData.newPlainText("Fast Paste", payload.text)
        }
    }

    private fun readUris(context: Context, uris: List<Uri>): ClipboardPayload? {
        val resolver = context.contentResolver
        // Copy file đã bị gỡ. Chỉ còn nhận một ảnh đơn từ clipboard.
        val uri = uris.firstOrNull() ?: return null
        val mime = resolver.getType(uri).orEmpty()
        if (!mime.startsWith("image/")) return null
        var bytes = resolver.openInputStream(uri)?.use { input ->
            readLimited(input, ClipboardPayload.MAX_PAYLOAD_BYTES)
        } ?: return null
        // Preserve the original caption/MIME after our own clipboard write.
        // Otherwise reading the URI changes its fingerprint and sends it back.
        if (uri.authority == "${context.packageName}.fileprovider") {
            val identity = uri.pathSegments.getOrNull(1).orEmpty()
            if (identity.matches(Regex("[a-f0-9]{64}"))) {
                val metadata = File(context.cacheDir, "clipboard/$identity/metadata.json")
                val original = runCatching {
                    restoreClipboardImage(metadata.readText(), ClipboardPayload.encode(bytes), identity)
                }.getOrNull()
                if (original != null) return original
            }
        }
        val wireMime = if (mime.lowercase() in setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/bmp")) {
            mime
        } else {
            // Convert phone-only formats (such as HEIC) without user settings.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 32L * 1024 * 1024) {
                "Không đọc được ảnh hoặc ảnh vượt 32 megapixel."
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val output = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            finally { bitmap.recycle() }
            bytes = output.toByteArray()
            require(bytes.size <= ClipboardPayload.MAX_PAYLOAD_BYTES) { "Ảnh vượt giới hạn 64 MB." }
            "image/png"
        }
        val hash = sha256(bytes).take(8)
        return ClipboardPayload(
            kind = ClipboardPayload.KIND_IMAGE,
            text = "[Hình ảnh · $hash]",
            mimeType = wireMime,
            data = ClipboardPayload.encode(bytes),
            thumbnail = createImageThumbnail(bytes)
        )
    }

    private fun writeImage(context: Context, payload: ClipboardPayload): ClipData {
        require(payload.data.isNotBlank() && payload.isWithinLimit()) { "Ảnh chưa tải xong." }
        val root = File(context.cacheDir, "clipboard").also { it.mkdirs() }
        cleanupOldClipboardFiles(root)
        val folder = File(root, payload.fingerprint()).also { it.mkdirs() }
        val target = File(folder, "clipboard-image.${extensionForMime(payload.mimeType)}")
        target.writeBytes(ClipboardPayload.decode(payload.data))
        File(folder, "metadata.json").writeText(payload.metadataJson().toString())
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        return ClipData.newUri(context.contentResolver, "Fast Paste", uri)
    }

    private fun readLimited(input: InputStream, remaining: Int): ByteArray? {
        if (remaining <= 0) {
            return null
        }
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

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
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

    private fun createImageThumbnail(bytes: ByteArray): String {
        // Decode a sampled preview, not a full-resolution phone photograph.
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return ""
        options.inSampleSize = thumbnailSampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return ""
        val longest = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = minOf(1f, THUMBNAIL_EDGE.toFloat() / longest)
        val preview = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        preview.compress(Bitmap.CompressFormat.JPEG, 76, output)
        if (preview !== bitmap) preview.recycle()
        bitmap.recycle()
        val encoded = ClipboardPayload.encode(output.toByteArray())
        return if (encoded.length <= ClipboardPayload.MAX_THUMBNAIL_CHARS) {
            "data:image/jpeg;base64,$encoded"
        } else {
            ""
        }
    }

    private const val MAX_CACHE_FOLDERS = 50
    private const val THUMBNAIL_EDGE = 256
}
