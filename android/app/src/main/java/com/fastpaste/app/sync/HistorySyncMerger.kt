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

internal fun ClipboardService.sendHistorySync(client: WebSocketClient) {
        scope.launch {
            try {
                val entries = dao.getRecentOnce(ClipboardService.MAX_HISTORY_ITEMS)
                // A known item excluded by the delta cursor is still known.
                // Re-stamping it as a fresh clip would overwrite the PC's
                // newer clipboard on reconnect.
                val seen = entries.mapTo(mutableSetOf()) { it.content }
                val history = JSONArray()
                val since = if (client.isSecure) client.remoteSyncCursor else 0L
                entries.asSequence().filter { !client.isSecure || it.timestamp > since }.forEach { entry ->
                    if (deletedHistoryStore.isDeleted(entry.content, entry.timestamp, entry.pinned)) {
                        return@forEach
                    }
                    seen.add(entry.content)
                    history.put(JSONObject()
                        .put("text", entry.content)
                        .put("timestamp", entry.timestamp)
                        .put("source", if (entry.source == "REMOTE") "PC" else "ANDROID")
                        .put("sourceApp", entry.sourceApp)
                        .put("sourceTitle", entry.sourceTitle)
                        .put("sourceIcon", entry.sourceIcon)
                        .put("pinned", entry.pinned)
                        .put("folder", entry.folder)
                        .also { item ->
                            val entryPayload = ClipboardPayload.fromEntry(entry)
                            if (entryPayload.kind != ClipboardPayload.KIND_TEXT) {
                                val blobId = entry.blobId.ifBlank { entryPayload.fingerprint() }
                                item.put("blobId", blobId)
                                    .put("blobSize", entryPayload.encodedSize())
                                    .put("blobReady", entryPayload.kind == ClipboardPayload.KIND_HTML && entryPayload.hasBody())
                                    .put("payload", entryPayload.metadataJson())
                            }
                        }
                    )
                }

                val currentPayload = runCatching {
                    AndroidClipboardCodec.read(this@sendHistorySync, clipboardManager.primaryClip)
                }.getOrNull()
                val currentText = currentPayload?.text
                if (
                    !currentText.isNullOrBlank() &&
                    !seen.contains(currentText) &&
                    !deletedHistoryStore.hasMarker(currentText)
                ) {
                    val timestamp = System.currentTimeMillis()
                    historyRepository.mergeEntry(
                        content = currentText,
                        source = "LOCAL",
                        timestamp = timestamp,
                        payload = currentPayload
                    )
                    history.put(JSONObject()
                        .put("text", currentText)
                        .put("timestamp", timestamp)
                        .put("source", "ANDROID")
                        .put("sourceApp", "")
                        .put("sourceTitle", "")
                        .put("sourceIcon", "")
                        .put("pinned", false)
                        .put("folder", "")
                        .also { item ->
                            if (currentPayload.kind != ClipboardPayload.KIND_TEXT) {
                                if (client.isSecure) {
                                    item.put("blobId", currentPayload.fingerprint())
                                        .put("blobSize", currentPayload.encodedSize())
                                        .put("blobReady", currentPayload.kind == ClipboardPayload.KIND_HTML && currentPayload.hasBody())
                                        .put("payload", currentPayload.metadataJson())
                                } else {
                                    item.put("payload", currentPayload.toJson())
                                }
                            }
                        }
                    )
                }

                val payload = JSONObject()
                    .put("app", "fastpaste")
                    .put("type", if (client.isSecure) "history_delta" else "history_sync")
                    .put("version", if (client.isSecure) 2 else 1)
                    .put("since", since)
                    .put("cursor", (0 until history.length()).maxOfOrNull {
                        history.getJSONObject(it).optLong("timestamp")
                    } ?: since)
                    .put("entries", history)
                if (client.isSecure) client.send(payload.toString())
                else client.send(encryptionStore.protect(payload.toString()))
                // A previous session may have saved metadata/cursor but lost
                // the body. A zero-entry delta must still resume that image.
                if (client.isSecure) {
                    dao.getLatestOnce()?.takeIf { it.source == "REMOTE" && !it.blobReady && it.blobId.isNotBlank() }
                        ?.let { requestBlob(it, applyToClipboard = true) }
                }
                ClipboardService.connectionEvents.tryEmit("Đã gửi ${history.length()} mục lịch sử sang PC")
            } catch (e: Exception) {
                Log.e(ClipboardService.TAG, "History sync send failed: ${e.message}")
                ClipboardService.connectionEvents.tryEmit("Gửi lịch sử lỗi: ${e.message ?: "không rõ"}")
            }
        }
    }

internal suspend fun ClipboardService.mergeHistorySync(entries: JSONArray, cursor: Long = 0L) {
        val latestLocalTimestamp = dao.getLatestOnce()?.timestamp ?: 0L
        var newestIncomingPayload: ClipboardPayload? = null
        var newestIncomingTimestamp = 0L
        var newestIncomingReady = true
        var newestIncomingBlobId = ""
        var inserted = 0

        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue
            val incomingPayload = item.optJSONObject("payload")
                ?.let(ClipboardPayload::fromJson)
                ?: ClipboardPayload.text(text)
            if (!incomingPayload.isWithinLimit()) continue

            val timestamp = item.optLong("timestamp", System.currentTimeMillis())
            val pinned = item.optBoolean("pinned", false)
            if (deletedHistoryStore.isDeleted(text, timestamp, pinned)) continue

            val source = if (item.optString("source") == "ANDROID") "LOCAL" else "REMOTE"
            val sourceApp = item.optString("sourceApp", item.optString("source_app", ""))
            val sourceTitle = item.optString("sourceTitle", item.optString("source_title", ""))
            val sourceIcon = item.optString("sourceIcon", item.optString("source_icon", ""))
            val folder = ClipboardRepository.cleanFolderName(item.optString("folder", ""))
            val blobId = item.optString("blobId")
            val blobSize = item.optLong("blobSize", 0L)
            val blobReady = item.optBoolean("blobReady", true) ||
                (incomingPayload.kind == ClipboardPayload.KIND_HTML && incomingPayload.hasBody())
            if (timestamp > newestIncomingTimestamp) {
                newestIncomingTimestamp = timestamp
                newestIncomingPayload = incomingPayload
                newestIncomingReady = blobReady
                newestIncomingBlobId = blobId
            }

            val mergeResult = historyRepository.mergeEntry(
                content = text,
                source = source,
                sourceApp = sourceApp,
                sourceTitle = sourceTitle,
                sourceIcon = sourceIcon,
                timestamp = timestamp,
                pinned = pinned,
                folder = folder,
                payload = incomingPayload,
                blobId = blobId,
                blobSize = blobSize,
                blobReady = blobReady
            )
            if (mergeResult.inserted) {
                inserted++
            }
        }

        val payloadToApply = newestIncomingPayload
        if (newestIncomingTimestamp > latestLocalTimestamp && payloadToApply != null) {
            if (!newestIncomingReady && newestIncomingBlobId.isNotBlank()) {
                dao.getByBlobId(newestIncomingBlobId)?.let { entry ->
                    if (entry.blobReady) receiveClipboardPayload(ClipboardPayload.fromEntry(entry))
                    else requestBlob(entry, applyToClipboard = true)
                }
            } else if (newestIncomingReady) {
                receiveClipboardPayload(payloadToApply)
            }
        }

        Log.d(ClipboardService.TAG, "Merged history sync: $inserted new items")
        if (cursor > 0L) {
            currentHost?.let { host ->
                pairingStore.peerForHost(host)?.let { pairingStore.updateSyncCursor(it.desktopId, cursor) }
            }
        }
        ClipboardService.connectionEvents.tryEmit("Đã nhận đồng bộ từ PC: thêm $inserted mục mới")
    }

