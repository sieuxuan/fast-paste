package com.fastpaste.app.security

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Resumable blob staging whose individual chunks are AES-GCM sealed with the
 * Android Keystore vault key. Only offsets and chunk lengths are visible on disk.
 */
class EncryptedTransferStore(context: Context) {
    private val root = File(context.applicationContext.cacheDir, "fastpaste-transfers-v2")
    private val secrets = SecureSecretStore(context.applicationContext)

    @Synchronized
    fun cleanupOldTransfers() = cleanupTransferCache(root)

    @Synchronized
    fun nextOffset(blobId: String): Long = chunkFiles(blobId).fold(0L) { expected, chunk ->
        if (chunk.offset != expected) return@fold expected
        expected + chunk.length
    }

    @Synchronized
    fun append(blobId: String, offset: Long, plain: ByteArray) {
        require(offset == nextOffset(blobId)) { "Blob chunk không liên tục." }
        require(plain.isNotEmpty()) { "Blob chunk rỗng." }
        val directory = directory(blobId).apply { mkdirs() }
        val target = File(directory, "${offset}_${plain.size}.chunk")
        val temporary = File(directory, "${target.name}.tmp")
        val sealed = secrets.seal(plain, aad(blobId, offset, plain.size.toLong()))
        FileOutputStream(temporary).use { output ->
            output.write(sealed)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            error("Không chốt được chunk đã mã hoá.")
        }
    }

    @Synchronized
    fun readComplete(blobId: String, total: Long): ByteArray {
        require(total in 1..MAX_STAGED_BYTES) { "Kích thước blob tạm không hợp lệ." }
        val output = ByteArrayOutputStream(total.toInt())
        var expected = 0L
        chunkFiles(blobId).forEach { chunk ->
            require(chunk.offset == expected) { "Blob tạm bị thiếu chunk." }
            val plain = secrets.open(
                chunk.file.readBytes(),
                aad(blobId, chunk.offset, chunk.length)
            )
            require(plain.size.toLong() == chunk.length) { "Độ dài chunk tạm không khớp." }
            output.write(plain)
            expected += plain.size
        }
        require(expected == total) { "Blob tạm chưa tải đủ." }
        return output.toByteArray()
    }

    @Synchronized
    fun clear(blobId: String) {
        directory(blobId).deleteRecursively()
    }

    private fun chunkFiles(blobId: String): List<ChunkFile> = directory(blobId)
        .listFiles()
        .orEmpty()
        .mapNotNull { file ->
            if (!file.name.endsWith(".chunk")) return@mapNotNull null
            val parts = file.name.removeSuffix(".chunk").split('_')
            val offset = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val length = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            if (offset < 0 || length <= 0) return@mapNotNull null
            ChunkFile(file, offset, length)
        }
        .sortedBy(ChunkFile::offset)

    private fun directory(blobId: String): File {
        val safe = blobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(96)
        require(safe.isNotBlank()) { "Blob id không hợp lệ." }
        return File(root, safe)
    }

    private fun aad(blobId: String, offset: Long, length: Long): ByteArray =
        "fastpaste-transfer-v2|$blobId|$offset|$length".toByteArray(Charsets.UTF_8)

    private data class ChunkFile(val file: File, val offset: Long, val length: Long)

    companion object {
        private const val MAX_STAGED_BYTES = 96L * 1024L * 1024L
    }
}
