package com.fastpaste.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.fastpaste.app.FastPasteApp
import com.fastpaste.app.MainActivity
import com.fastpaste.app.R
import com.fastpaste.app.data.ClipboardPayload
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.data.ClipboardRepository
import com.fastpaste.app.discovery.ServiceDiscovery
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.sync.AndroidClipboardCodec
import com.fastpaste.app.sync.DeletedHistoryStore
import com.fastpaste.app.sync.EncryptionStore
import com.fastpaste.app.security.EncryptedTransferStore
import com.fastpaste.app.security.PairingStore
import com.fastpaste.app.websocket.ConnectionState
import com.fastpaste.app.websocket.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.nio.ByteBuffer

internal suspend fun ClipboardService.handleBlobOffer(json: JSONObject) {
        val blobId = json.optString("blobId")
        val payloadJson = json.optJSONObject("payload") ?: return
        if (blobId.isBlank()) return
        val payload = ClipboardPayload.fromJson(payloadJson)
        val content = json.optString("text", payload.text)
        val blobSize = json.optLong("blobSize", 0L)
        if (content.isBlank() || payload.kind == ClipboardPayload.KIND_TEXT ||
            !payload.isWithinLimit() ||
            blobSize !in 1L..ClipboardPayload.MAX_PAYLOAD_BYTES.toLong()
        ) return
        historyRepository.mergeEntry(
            content = content,
            source = "REMOTE",
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            payload = payload,
            blobId = blobId,
            blobSize = blobSize,
            blobReady = false
        )
        dao.getByBlobId(blobId)?.let { requestBlob(it, applyToClipboard = true) }
    }

internal fun ClipboardService.requestBlob(entry: ClipboardEntry, applyToClipboard: Boolean) {
        if (entry.blobId.isBlank()) return
        if (incomingTransfers.values.any { it.blobId == entry.blobId }) return
        val transferId = PairingStore.randomId()
        val transfer = ClipboardService.IncomingTransfer(transferId, entry.blobId, entry.id, applyToClipboard)
        incomingTransfers[transferId] = transfer
        sendBlobRequest(transfer, initial = true)
        scope.launch {
            var retries = 0
            var lastOffset = transferStore.nextOffset(entry.blobId)
            while (incomingTransfers[transferId] === transfer) {
                delay(ClipboardService.ACK_TIMEOUT_MS)
                val currentOffset = transferStore.nextOffset(entry.blobId)
                if (currentOffset > lastOffset) {
                    lastOffset = currentOffset
                    retries = 0
                    continue
                }
                if (System.currentTimeMillis() - transfer.lastProgressAt < ClipboardService.ACK_TIMEOUT_MS) continue
                if (++retries > ClipboardService.MAX_TRANSFER_RETRIES) {
                    setTransferProgress(
                        TransferProgress(transferId, entry.content, transferStore.nextOffset(entry.blobId), entry.blobSize, "download", "Lỗi")
                    )
                    ClipboardService.connectionEvents.tryEmit("Tải blob thất bại sau $ClipboardService.MAX_TRANSFER_RETRIES lần thử")
                    incomingTransfers.remove(transferId)
                    break
                }
                sendBlobRequest(transfer, initial = false)
            }
        }
    }

internal fun ClipboardService.sendBlobRequest(transfer: ClipboardService.IncomingTransfer, initial: Boolean) {
        val offset = transferStore.nextOffset(transfer.blobId)
        sendWire(
            JSONObject()
                .put("app", "fastpaste")
                .put("type", if (initial) "blob_request" else "blob_ack")
                .put("version", 2)
                .put("transferId", transfer.transferId)
                .put("blobId", transfer.blobId)
                .put("nextOffset", offset)
                .put("offset", offset)
                .put("chunkSize", ClipboardService.TRANSFER_CHUNK_BYTES)
                .put("windowSize", transfer.windowSize)
                .toString()
        )
    }

internal suspend fun ClipboardService.sendOutgoingBlobChunk(json: JSONObject) {
        val transferId = json.optString("transferId")
        val blobId = json.optString("blobId")
        val offset = json.optLong("nextOffset", json.optLong("offset", 0L)).coerceAtLeast(0L)
        if (transferId.isBlank() || blobId.isBlank()) return
        val payload = outgoingPayloads[blobId]
            ?: dao.getByBlobId(blobId)?.takeIf { it.blobReady }?.let(ClipboardPayload::fromEntry)
            ?: return
        val bytes = payload.toJson().toString().toByteArray(Charsets.UTF_8)
        if (offset > bytes.size) return
        if (offset == bytes.size.toLong()) {
            sendBlobComplete(transferId, blobId)
            handleBlobComplete(json)
            return
        }
        val windowSize = json.optInt("windowSize", ClipboardService.DEFAULT_WINDOW_SIZE).coerceIn(1, ClipboardService.MAX_WINDOW_SIZE)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        var end = offset.toInt()
        repeat(windowSize) {
            if (end >= bytes.size) return@repeat
            val start = end
            end = minOf(bytes.size, start + ClipboardService.TRANSFER_CHUNK_BYTES.toInt())
            val frame = encodeBinaryChunk(
                transferId, blobId, start.toLong(), bytes.size.toLong(), hash,
                end == bytes.size, windowSize, bytes.copyOfRange(start, end)
            )
            wsClient?.sendBinary(frame)
        }
        setTransferProgress(
            TransferProgress(transferId, payload.text, end.toLong(), bytes.size.toLong(), "upload", if (end == bytes.size) "Chờ ACK" else "Đang gửi")
        )
    }

internal suspend fun ClipboardService.handleIncomingBinaryChunk(frame: ByteArray) {
        val chunk = decodeBinaryChunk(frame) ?: return
        if (chunk.total !in 1L..ClipboardService.MAX_TRANSFER_JSON_BYTES ||
            chunk.offset < 0L || chunk.offset > chunk.total ||
            chunk.data.isEmpty() || chunk.data.size.toLong() > chunk.total - chunk.offset
        ) return
        val transfer = incomingTransfers[chunk.transferId] ?: return
        if (chunk.blobId != transfer.blobId) return
        val expectedOffset = transferStore.nextOffset(transfer.blobId)
        if (chunk.offset != expectedOffset) {
            transfer.windowSize = (transfer.windowSize / 2).coerceAtLeast(1)
            transfer.lastProgressAt = 0L
            sendBlobRequest(transfer, initial = false)
            return
        }
        // A resume can change the advertised window; ACK that exact window.
        transfer.windowSize = chunk.windowSize
        transferStore.append(transfer.blobId, chunk.offset, chunk.data)
        transfer.lastProgressAt = System.currentTimeMillis()
        transfer.chunksSinceAck++
        val nextOffset = transferStore.nextOffset(transfer.blobId)
        setTransferProgress(
            TransferProgress(chunk.transferId, transfer.blobId.take(12), nextOffset, chunk.total, "download", if (nextOffset >= chunk.total) "Đang kiểm tra" else "Đang tải")
        )
        if (nextOffset < chunk.total) {
            if (transfer.chunksSinceAck >= transfer.windowSize) {
                transfer.chunksSinceAck = 0
                transfer.windowSize = (transfer.windowSize + 1).coerceAtMost(ClipboardService.MAX_WINDOW_SIZE)
                sendBlobRequest(transfer, initial = false)
            }
            return
        }
        val bytes = runCatching { transferStore.readComplete(transfer.blobId, chunk.total) }
            .getOrElse {
                transferStore.clear(transfer.blobId)
                transfer.windowSize = 1
                sendBlobRequest(transfer, initial = true)
                return
            }
        if (bytes.size.toLong() != chunk.total || !MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(chunk.hash)) {
            transferStore.clear(transfer.blobId)
            transfer.windowSize = 1
            sendBlobRequest(transfer, initial = true)
            return
        }
        completeIncomingBlob(transfer, chunk.transferId, chunk.total, bytes)
    }

internal suspend fun ClipboardService.completeIncomingBlob(
        transfer: ClipboardService.IncomingTransfer,
        transferId: String,
        total: Long,
        bytes: ByteArray
    ) {
        val payload = runCatching {
            ClipboardPayload.fromJson(JSONObject(bytes.toString(Charsets.UTF_8))).also {
                require(it.isWithinLimit()) { "Ảnh vượt giới hạn clipboard." }
                require(it.matchesFingerprint(transfer.blobId)) { "Dữ liệu ảnh không khớp; đang tải lại." }
            }
        }.getOrElse { error ->
            transferStore.clear(transfer.blobId)
            transfer.lastProgressAt = 0L
            transfer.chunksSinceAck = 0
            transfer.windowSize = 1
            sendBlobRequest(transfer, initial = true)
            ClipboardService.connectionEvents.tryEmit(error.message.orEmpty())
            return
        }
        val entry = dao.getById(transfer.entryId) ?: dao.getByBlobId(transfer.blobId) ?: return
        historyRepository.mergeEntry(
            content = entry.content, source = entry.source, sourceApp = entry.sourceApp,
            sourceTitle = entry.sourceTitle, sourceIcon = entry.sourceIcon, timestamp = entry.timestamp,
            pinned = entry.pinned, folder = entry.folder, promoteExisting = true, payload = payload,
            blobId = transfer.blobId, blobSize = payload.encodedSize(), blobReady = true
        )
        if (transfer.applyToClipboard) {
            val clip = AndroidClipboardCodec.write(this@completeIncomingBlob, payload)
            fingerprintGate.markApplied(payload.fingerprint())
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(clip)
            }
        }
        incomingTransfers.remove(transferId)
        transferStore.clear(transfer.blobId)
        sendBlobComplete(transferId, transfer.blobId)
        setTransferProgress(TransferProgress(transferId, entry.content, total, total, "download", "Hoàn tất"))
        ClipboardService.connectionEvents.tryEmit("Đã tải và xác thực blob ${transfer.blobId.take(12)}")
        scope.launch {
            delay(1_500)
            ClipboardService.transferProgress.value = ClipboardService.transferProgress.value.filterNot { it.transferId == transferId }
        }
    }

internal suspend fun ClipboardService.handleIncomingBlobChunk(json: JSONObject) {
        val transferId = json.optString("transferId")
        val transfer = incomingTransfers[transferId] ?: return
        if (json.optString("blobId") != transfer.blobId) return
        val expectedOffset = transferStore.nextOffset(transfer.blobId)
        val offset = json.optLong("offset", -1L)
        val total = json.optLong("total", 0L)
        if (total !in 1L..ClipboardService.MAX_TRANSFER_JSON_BYTES ||
            offset < 0L || offset > total
        ) return
        if (offset != expectedOffset) {
            transfer.lastProgressAt = 0L
            sendBlobRequest(transfer, initial = false)
            return
        }
        val chunk = runCatching { PairingStore.urlDecode(json.getString("data")) }.getOrNull() ?: return
        if (chunk.isEmpty() || chunk.size.toLong() > total - offset) return
        transferStore.append(transfer.blobId, offset, chunk)
        transfer.lastProgressAt = System.currentTimeMillis()
        val nextOffset = transferStore.nextOffset(transfer.blobId)
        setTransferProgress(
            TransferProgress(transferId, transfer.blobId.take(12), nextOffset, total, "download", if (nextOffset >= total) "Đang kiểm tra" else "Đang tải")
        )

        if (nextOffset < total) {
            sendBlobRequest(transfer, initial = false)
            return
        }

        val bytes = runCatching { transferStore.readComplete(transfer.blobId, total) }
            .getOrElse {
                transferStore.clear(transfer.blobId)
                transfer.lastProgressAt = 0L
                sendBlobRequest(transfer, initial = true)
                return
            }
        if (bytes.size.toLong() != total || sha256Hex(bytes) != json.optString("hash")) {
            transferStore.clear(transfer.blobId)
            transfer.lastProgressAt = 0L
            sendBlobRequest(transfer, initial = true)
            return
        }
        completeIncomingBlob(transfer, transferId, total, bytes)
    }

internal fun ClipboardService.sendBlobComplete(transferId: String, blobId: String) {
        sendWire(
            JSONObject()
                .put("app", "fastpaste")
                .put("type", "blob_complete")
                .put("version", 2)
                .put("transferId", transferId)
                .put("blobId", blobId)
                .toString()
        )
    }

internal fun ClipboardService.handleBlobComplete(json: JSONObject) {
        val transferId = json.optString("transferId")
        if (transferId.isBlank()) return
        outgoingPayloads.remove(json.optString("blobId"))
        val previous = ClipboardService.transferProgress.value.firstOrNull { it.transferId == transferId }
        if (previous != null) {
            setTransferProgress(previous.copy(sentBytes = previous.totalBytes, status = "Hoàn tất"))
        }
        scope.launch {
            delay(1_500)
            ClipboardService.transferProgress.value = ClipboardService.transferProgress.value.filterNot { it.transferId == transferId }
        }
    }

internal fun ClipboardService.setTransferProgress(progress: TransferProgress) {
        ClipboardService.transferProgress.value = (ClipboardService.transferProgress.value.filterNot { it.transferId == progress.transferId } + progress)
            .takeLast(4)
    }

internal data class BinaryChunk(
        val transferId: String,
        val blobId: String,
        val offset: Long,
        val total: Long,
        val hash: ByteArray,
        val windowSize: Int,
        val data: ByteArray
    )

internal fun ClipboardService.encodeBinaryChunk(
        transferId: String,
        blobId: String,
        offset: Long,
        total: Long,
        hash: ByteArray,
        eof: Boolean,
        windowSize: Int,
        data: ByteArray
    ): ByteArray {
        val transfer = transferId.toByteArray(Charsets.UTF_8)
        val blob = blobId.toByteArray(Charsets.UTF_8)
        require(transfer.size <= 255 && blob.size <= 255 && hash.size == 32)
        return ByteBuffer.allocate(56 + transfer.size + blob.size + data.size)
            .put(ClipboardService.BINARY_MAGIC)
            .put(if (eof) 1 else 0)
            .put(transfer.size.toByte())
            .put(blob.size.toByte())
            .put(windowSize.coerceIn(1, ClipboardService.MAX_WINDOW_SIZE).toByte())
            .putLong(offset)
            .putLong(total)
            .put(hash)
            .put(transfer)
            .put(blob)
            .put(data)
            .array()
    }

internal fun ClipboardService.decodeBinaryChunk(frame: ByteArray): BinaryChunk? {
        if (frame.size < 56 || !frame.copyOfRange(0, 4).contentEquals(ClipboardService.BINARY_MAGIC)) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.position(4)
        buffer.get() // flags
        val transferLength = buffer.get().toInt() and 0xff
        val blobLength = buffer.get().toInt() and 0xff
        val windowSize = (buffer.get().toInt() and 0xff).coerceIn(1, ClipboardService.MAX_WINDOW_SIZE)
        val offset = buffer.long
        val total = buffer.long
        val hash = ByteArray(32).also(buffer::get)
        if (56 + transferLength + blobLength > frame.size || total !in 0..ClipboardService.MAX_TRANSFER_JSON_BYTES) return null
        val transfer = ByteArray(transferLength).also(buffer::get).toString(Charsets.UTF_8)
        val blob = ByteArray(blobLength).also(buffer::get).toString(Charsets.UTF_8)
        val data = ByteArray(buffer.remaining()).also(buffer::get)
        return BinaryChunk(transfer, blob, offset, total, hash, windowSize, data)
    }

internal fun ClipboardService.sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

