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
import java.util.concurrent.ConcurrentHashMap

data class TransferProgress(
    val transferId: String,
    val label: String,
    val sentBytes: Long,
    val totalBytes: Long,
    val direction: String,
    val status: String
)

class ClipboardService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager
    private var wsClient: WebSocketClient? = null
    // Per-client scope: cancelling it tears down the client's collectors AND
    // its pending reconnect jobs in one shot.
    private var clientScope: CoroutineScope? = null
    private var currentHost: String? = null
    private var currentPort = 0
    // Service-owned discovery keeps running in the background (the ViewModel's
    // discovery dies with the UI), so a PC coming back on a new IP is found.
    private var backgroundDiscovery: ServiceDiscovery? = null
    private var lastSyncedFingerprint = ""
    private val dao by lazy { (application as FastPasteApp).database.clipboardDao() }
    private val historyRepository by lazy { ClipboardRepository(dao) }
    private val deletedHistoryStore by lazy { DeletedHistoryStore(applicationContext) }
    private val encryptionStore by lazy { EncryptionStore(applicationContext) }
    private val pairingStore by lazy { PairingStore(applicationContext) }
    private val transferStore by lazy { EncryptedTransferStore(applicationContext) }
    private val outgoingPayloads = ConcurrentHashMap<String, ClipboardPayload>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()

    private data class IncomingTransfer(
        val transferId: String,
        val blobId: String,
        val entryId: Long,
        val applyToClipboard: Boolean,
        var lastProgressAt: Long = System.currentTimeMillis(),
        var windowSize: Int = DEFAULT_WINDOW_SIZE,
        var chunksSinceAck: Int = 0
    )

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncCurrentClipboard()
    }

    private fun syncCurrentClipboard() {
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
        scope.launch {
            val payload = AndroidClipboardCodec.read(this@ClipboardService, clip)
                ?: return@launch
            val fingerprint = payload.fingerprint()
            if (fingerprint == lastSyncedFingerprint) return@launch
            lastSyncedFingerprint = fingerprint
            sendClipboardPayload(payload)
            saveToHistory(payload, "LOCAL")
            Log.d(TAG, "Sent ${payload.kind}: ${payload.text.take(60)}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 4567)
                if (host == currentHost && port == currentPort && wsClient != null) {
                    // Already managing this target — don't tear down the client
                    // (that would reset its backoff); just retry right away.
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification("Đang đồng bộ với $host:$port")
                    )
                    wsClient?.retryNow()
                } else {
                    startSync(host, port)
                }
            }
            ACTION_STOP -> {
                activeTarget.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_COPY_HISTORY_ITEM -> {
                val id = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
                if (id >= 0L) {
                    scope.launch {
                        copyHistoryItem(id)
                        if (activeTarget.value == null) stopSelf(startId)
                    }
                }
            }
            ACTION_SYNC_CURRENT_CLIP -> syncCurrentClipboard()
        }
        return START_STICKY
    }

    private fun startSync(host: String, port: Int) {
        val notification = buildNotification("Đang kết nối tới $host:$port...")
        startForeground(NOTIFICATION_ID, notification)

        currentHost = host
        currentPort = port
        activeTarget.value = "$host:$port"

        // Cancel the previous client's collectors BEFORE disconnecting, so its
        // final DISCONNECTED can't be published as a spurious blip mid-handoff.
        clientScope?.cancel()
        wsClient?.disconnect()

        val cs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = cs
        wsClient = WebSocketClient(applicationContext, cs).also { client ->
            client.connect(host, port)

            // Receive clipboard from desktop
            cs.launch {
                client.messages.collect { message ->
                    handleIncomingMessage(message)
                }
            }

            cs.launch {
                client.binaryMessages.collect { frame ->
                    handleIncomingBinaryChunk(frame)
                }
            }

            cs.launch {
                client.events.collect { event ->
                    connectionEvents.tryEmit(event)
                }
            }

            // Update notification with connection status
            cs.launch {
                client.state.collectLatest { state ->
                    connectionState.value = state
                    val status = when (state) {
                        ConnectionState.CONNECTED -> {
                            stopBackgroundDiscovery()
                            sendHistorySync(client)
                            Log.d(TAG, "Connected; exchanging clipboard history")
                            connectionEvents.tryEmit("Đã kết nối tới $host:$port")
                            "Đã kết nối tới $host"
                        }
                        ConnectionState.CONNECTING -> "Đang kết nối tới $host..."
                        ConnectionState.DISCONNECTED -> {
                            ensureBackgroundDiscovery()
                            "Đã ngắt kết nối, đang thử lại..."
                        }
                    }
                    updateNotification(status)
                }
            }
        }

        // Re-register instead of stacking a duplicate listener on reconnect
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    /**
     * While disconnected, scan for the PC ourselves: the client's retry loop
     * only knows the last IP, and the ViewModel's discovery dies with the UI.
     * Only a target CHANGE triggers a reconnect here — rediscovering the same
     * address is left to the client's own backoff, avoiding connect storms.
     */
    private fun ensureBackgroundDiscovery() {
        val discovery = backgroundDiscovery ?: ServiceDiscovery(applicationContext).also { d ->
            backgroundDiscovery = d
            scope.launch {
                d.servers.collect { servers ->
                    val activePeer = currentHost?.let(pairingStore::peerForHost)
                    val server = servers.firstOrNull { candidate ->
                        activePeer != null && (
                            candidate.desktopId == activePeer.desktopId ||
                                candidate.desktopId.isBlank() && candidate.host == activePeer.host
                            ) ||
                            activePeer == null && pairingStore.peerForHost(candidate.host) != null
                    } ?: return@collect
                    if (server.desktopId.isNotBlank()) {
                        pairingStore.rebind(server.desktopId, server.host, server.port)
                    }
                    val target = "${server.host}:${server.port}"
                    if (target != activeTarget.value &&
                        connectionState.value != ConnectionState.CONNECTED
                    ) {
                        Log.d(TAG, "PC reappeared at new address $target — switching")
                        connectionEvents.tryEmit("Tìm thấy PC ở địa chỉ mới $target, đang chuyển kết nối")
                        startSync(server.host, server.port)
                    }
                }
            }
        }
        discovery.startDiscovery(cycle = true)
    }

    private fun stopBackgroundDiscovery() {
        backgroundDiscovery?.stopDiscovery()
    }

    private suspend fun handleIncomingMessage(message: String) {
        if (wsClient?.isSecure == true) {
            handleDecodedMessage(message)
            return
        }
        val decrypted = runCatching { encryptionStore.decryptIfEncrypted(message) }
            .getOrElse { error ->
                connectionEvents.tryEmit("Không giải mã được dữ liệu: ${error.message ?: "sai khoá"}")
                return
            }
        if (encryptionStore.isEnabled && decrypted == null) {
            connectionEvents.tryEmit("Đã chặn dữ liệu plaintext vì E2EE đang bật")
            return
        }
        handleDecodedMessage(decrypted ?: message)
    }

    private fun sendClipboardPayload(payload: ClipboardPayload): Boolean {
        if (payload.kind == ClipboardPayload.KIND_TEXT) return sendWire(payload.text)
        if (wsClient?.isSecure != true) return sendWire(payload.protocolJson())
        val blobId = payload.fingerprint()
        outgoingPayloads[blobId] = payload
        return sendWire(
            JSONObject()
                .put("app", "fastpaste")
                .put("type", "clipboard_blob_offer")
                .put("version", 2)
                .put("text", payload.text)
                .put("timestamp", System.currentTimeMillis())
                .put("source", "ANDROID")
                .put("blobId", blobId)
                .put("blobSize", payload.encodedSize())
                .put("payload", payload.metadataJson())
                .toString()
        )
    }

    private suspend fun handleDecodedMessage(decodedMessage: String) {
        val json = runCatching { JSONObject(decodedMessage) }.getOrNull()
        if (json?.optString("app") == "fastpaste") {
            runCatching {
                when (json.optString("type")) {
                "history_sync" -> {
                    mergeHistorySync(json.optJSONArray("entries") ?: JSONArray())
                }

                "history_delta" -> {
                    mergeHistorySync(
                        json.optJSONArray("entries") ?: JSONArray(),
                        json.optLong("cursor", 0L)
                    )
                }

                "clipboard_blob_offer" -> {
                    handleBlobOffer(json)
                }

                "blob_request", "blob_ack" -> {
                    sendOutgoingBlobChunk(json)
                }

                "blob_chunk" -> {
                    handleIncomingBlobChunk(json)
                }

                "blob_complete" -> {
                    handleBlobComplete(json)
                }

                "clipboard_payload" -> {
                    json.optJSONObject("payload")?.let {
                        receiveClipboardPayload(ClipboardPayload.fromJson(it))
                    }
                }

                else -> Log.w(TAG, "Ignored unknown FastPaste protocol ${json.optString("type")}")
            }
            }.onFailure { error ->
                Log.e(TAG, "Protocol message failed: ${error.message}")
                connectionEvents.tryEmit("Gói đồng bộ bị từ chối: ${error.message ?: "không hợp lệ"}")
            }
            return
        }

        receiveClipboardPayload(ClipboardPayload.text(decodedMessage))
    }

    private suspend fun receiveClipboardPayload(payload: ClipboardPayload) {
        if (payload.text.isEmpty() || !payload.isWithinLimit()) return
        val fingerprint = payload.fingerprint()
        if (fingerprint == lastSyncedFingerprint) return
        lastSyncedFingerprint = fingerprint
        withContext(Dispatchers.Main) {
            clipboardManager.setPrimaryClip(AndroidClipboardCodec.write(this@ClipboardService, payload))
        }
        saveToHistory(payload, "REMOTE")
        Log.d(TAG, "Received ${payload.kind}: ${payload.text.take(60)}")
    }

    /**
     * Replays a saved entry deliberately. This must not go through the normal
     * clipboard listener's duplicate guard: selecting the current image again
     * is an explicit request to copy and resend its full binary payload.
     */
    private suspend fun copyHistoryItem(id: Long) {
        val entry = dao.getById(id)
        if (entry == null) {
            publishClipboardAction("Không tìm thấy mục lịch sử cần sao chép")
            return
        }
        if (!entry.blobReady && entry.blobId.isNotBlank()) {
            requestBlob(entry, applyToClipboard = true)
            publishClipboardAction("Đang tải ${entry.blobSize.coerceAtLeast(0L) / 1024} KB; sẽ tự sao chép khi hoàn tất")
            return
        }

        val payload = ClipboardPayload.fromEntry(entry)
        if (!payload.isWithinLimit()) {
            publishClipboardAction("Không thể sao chép: dữ liệu vượt quá giới hạn")
            return
        }

        val clip = runCatching {
            AndroidClipboardCodec.write(this@ClipboardService, payload)
        }.getOrElse { error ->
            publishClipboardAction("Không thể tạo clipboard: ${error.message ?: "dữ liệu không hợp lệ"}")
            return
        }

        lastSyncedFingerprint = payload.fingerprint()
        withContext(Dispatchers.Main) {
            clipboardManager.setPrimaryClip(clip)
        }

        val sent = sendClipboardPayload(payload)
        val label = when (payload.kind) {
            ClipboardPayload.KIND_IMAGE -> "ảnh"
            ClipboardPayload.KIND_FILES -> "tệp"
            ClipboardPayload.KIND_HTML -> "rich text"
            else -> "nội dung"
        }
        publishClipboardAction(
            if (sent) "Đã sao chép và gửi $label sang PC"
            else "Đã sao chép $label; PC chưa kết nối nên chưa gửi"
        )
    }

    private suspend fun publishClipboardAction(message: String) {
        connectionEvents.tryEmit(message)
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ClipboardService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendHistorySync(client: WebSocketClient) {
        scope.launch {
            try {
                val entries = dao.getRecentOnce(MAX_HISTORY_ITEMS)
                val seen = mutableSetOf<String>()
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
                                    .put("blobReady", false)
                                    .put("payload", entryPayload.metadataJson())
                            }
                        }
                    )
                }

                val currentPayload = AndroidClipboardCodec.read(
                    this@ClipboardService,
                    clipboardManager.primaryClip
                )
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
                                        .put("blobReady", false)
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
                    .put("cursor", entries.maxOfOrNull { it.timestamp } ?: since)
                    .put("entries", history)
                if (client.isSecure) client.send(payload.toString())
                else client.send(encryptionStore.protect(payload.toString()))
                connectionEvents.tryEmit("Đã gửi ${history.length()} mục lịch sử sang PC")
            } catch (e: Exception) {
                Log.e(TAG, "History sync send failed: ${e.message}")
                connectionEvents.tryEmit("Gửi lịch sử lỗi: ${e.message ?: "không rõ"}")
            }
        }
    }

    private suspend fun mergeHistorySync(entries: JSONArray, cursor: Long = 0L) {
        val latestLocalTimestamp = dao.getLatestOnce()?.timestamp ?: 0L
        var newestIncomingPayload: ClipboardPayload? = null
        var newestIncomingTimestamp = 0L
        var newestIncomingReady = true
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
            val blobReady = item.optBoolean("blobReady", true)
            if (timestamp > newestIncomingTimestamp) {
                newestIncomingTimestamp = timestamp
                newestIncomingPayload = incomingPayload
                newestIncomingReady = blobReady
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
        if (newestIncomingTimestamp > latestLocalTimestamp && payloadToApply != null && newestIncomingReady) {
            lastSyncedFingerprint = payloadToApply.fingerprint()
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(AndroidClipboardCodec.write(this@ClipboardService, payloadToApply))
            }
        }

        Log.d(TAG, "Merged history sync: $inserted new items")
        if (cursor > 0L) {
            currentHost?.let { host ->
                pairingStore.peerForHost(host)?.let { pairingStore.updateSyncCursor(it.desktopId, cursor) }
            }
        }
        connectionEvents.tryEmit("Đã nhận đồng bộ từ PC: thêm $inserted mục mới")
    }

    private suspend fun handleBlobOffer(json: JSONObject) {
        val blobId = json.optString("blobId")
        val payloadJson = json.optJSONObject("payload") ?: return
        if (blobId.isBlank()) return
        val payload = ClipboardPayload.fromJson(payloadJson)
        historyRepository.mergeEntry(
            content = json.optString("text", payload.text),
            source = "REMOTE",
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            payload = payload,
            blobId = blobId,
            blobSize = json.optLong("blobSize", 0L),
            blobReady = false
        )
        dao.getByBlobId(blobId)?.let { requestBlob(it, applyToClipboard = true) }
    }

    private fun requestBlob(entry: ClipboardEntry, applyToClipboard: Boolean) {
        if (entry.blobId.isBlank()) return
        val transferId = PairingStore.randomId()
        val transfer = IncomingTransfer(transferId, entry.blobId, entry.id, applyToClipboard)
        incomingTransfers[transferId] = transfer
        sendBlobRequest(transfer, initial = true)
        scope.launch {
            var retries = 0
            var lastOffset = transferStore.nextOffset(entry.blobId)
            while (incomingTransfers[transferId] === transfer) {
                delay(ACK_TIMEOUT_MS)
                val currentOffset = transferStore.nextOffset(entry.blobId)
                if (currentOffset > lastOffset) {
                    lastOffset = currentOffset
                    retries = 0
                    continue
                }
                if (System.currentTimeMillis() - transfer.lastProgressAt < ACK_TIMEOUT_MS) continue
                if (++retries > MAX_TRANSFER_RETRIES) {
                    setTransferProgress(
                        TransferProgress(transferId, entry.content, transferStore.nextOffset(entry.blobId), entry.blobSize, "download", "Lỗi")
                    )
                    connectionEvents.tryEmit("Tải blob thất bại sau $MAX_TRANSFER_RETRIES lần thử")
                    incomingTransfers.remove(transferId)
                    break
                }
                sendBlobRequest(transfer, initial = false)
            }
        }
    }

    private fun sendBlobRequest(transfer: IncomingTransfer, initial: Boolean) {
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
                .put("chunkSize", TRANSFER_CHUNK_BYTES)
                .put("windowSize", transfer.windowSize)
                .toString()
        )
    }

    private suspend fun sendOutgoingBlobChunk(json: JSONObject) {
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
        val windowSize = json.optInt("windowSize", DEFAULT_WINDOW_SIZE).coerceIn(1, MAX_WINDOW_SIZE)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        var end = offset.toInt()
        repeat(windowSize) {
            if (end >= bytes.size) return@repeat
            val start = end
            end = minOf(bytes.size, start + TRANSFER_CHUNK_BYTES.toInt())
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

    private suspend fun handleIncomingBinaryChunk(frame: ByteArray) {
        val chunk = decodeBinaryChunk(frame) ?: return
        val transfer = incomingTransfers[chunk.transferId] ?: return
        if (chunk.blobId != transfer.blobId) return
        val expectedOffset = transferStore.nextOffset(transfer.blobId)
        if (chunk.offset != expectedOffset) {
            transfer.windowSize = (transfer.windowSize / 2).coerceAtLeast(1)
            transfer.lastProgressAt = 0L
            sendBlobRequest(transfer, initial = false)
            return
        }
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
                transfer.windowSize = (transfer.windowSize + 1).coerceAtMost(MAX_WINDOW_SIZE)
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

    private suspend fun completeIncomingBlob(
        transfer: IncomingTransfer,
        transferId: String,
        total: Long,
        bytes: ByteArray
    ) {
        val payload = ClipboardPayload.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
        require(payload.fingerprint() == transfer.blobId) { "Blob fingerprint không khớp metadata." }
        val entry = dao.getById(transfer.entryId) ?: dao.getByBlobId(transfer.blobId) ?: return
        historyRepository.mergeEntry(
            content = entry.content, source = entry.source, sourceApp = entry.sourceApp,
            sourceTitle = entry.sourceTitle, sourceIcon = entry.sourceIcon, timestamp = entry.timestamp,
            pinned = entry.pinned, folder = entry.folder, promoteExisting = true, payload = payload,
            blobId = transfer.blobId, blobSize = payload.encodedSize(), blobReady = true
        )
        if (transfer.applyToClipboard) {
            lastSyncedFingerprint = payload.fingerprint()
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(AndroidClipboardCodec.write(this@ClipboardService, payload))
            }
        }
        incomingTransfers.remove(transferId)
        transferStore.clear(transfer.blobId)
        sendBlobComplete(transferId, transfer.blobId)
        setTransferProgress(TransferProgress(transferId, entry.content, total, total, "download", "Hoàn tất"))
        connectionEvents.tryEmit("Đã tải và xác thực blob ${transfer.blobId.take(12)}")
        scope.launch {
            delay(1_500)
            transferProgress.value = transferProgress.value.filterNot { it.transferId == transferId }
        }
    }

    private suspend fun handleIncomingBlobChunk(json: JSONObject) {
        val transferId = json.optString("transferId")
        val transfer = incomingTransfers[transferId] ?: return
        if (json.optString("blobId") != transfer.blobId) return
        val expectedOffset = transferStore.nextOffset(transfer.blobId)
        val offset = json.optLong("offset", -1L)
        if (offset != expectedOffset) {
            transfer.lastProgressAt = 0L
            sendBlobRequest(transfer, initial = false)
            return
        }
        val chunk = runCatching { PairingStore.urlDecode(json.getString("data")) }.getOrNull() ?: return
        transferStore.append(transfer.blobId, offset, chunk)
        transfer.lastProgressAt = System.currentTimeMillis()
        val nextOffset = transferStore.nextOffset(transfer.blobId)
        val total = json.optLong("total", 0L)
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
        val payload = ClipboardPayload.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
        require(payload.fingerprint() == transfer.blobId) { "Blob fingerprint không khớp metadata." }
        val entry = dao.getById(transfer.entryId) ?: dao.getByBlobId(transfer.blobId) ?: return
        historyRepository.mergeEntry(
            content = entry.content,
            source = entry.source,
            sourceApp = entry.sourceApp,
            sourceTitle = entry.sourceTitle,
            sourceIcon = entry.sourceIcon,
            timestamp = entry.timestamp,
            pinned = entry.pinned,
            folder = entry.folder,
            promoteExisting = true,
            payload = payload,
            blobId = transfer.blobId,
            blobSize = payload.encodedSize(),
            blobReady = true
        )
        if (transfer.applyToClipboard) {
            lastSyncedFingerprint = payload.fingerprint()
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(AndroidClipboardCodec.write(this@ClipboardService, payload))
            }
        }
        incomingTransfers.remove(transferId)
        transferStore.clear(transfer.blobId)
        sendBlobComplete(transferId, transfer.blobId)
        setTransferProgress(TransferProgress(transferId, entry.content, total, total, "download", "Hoàn tất"))
        connectionEvents.tryEmit("Đã tải và xác thực blob ${transfer.blobId.take(12)}")
        scope.launch {
            delay(1_500)
            transferProgress.value = transferProgress.value.filterNot { it.transferId == transferId }
        }
    }

    private fun sendBlobComplete(transferId: String, blobId: String) {
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

    private fun handleBlobComplete(json: JSONObject) {
        val transferId = json.optString("transferId")
        if (transferId.isBlank()) return
        val previous = transferProgress.value.firstOrNull { it.transferId == transferId }
        if (previous != null) {
            setTransferProgress(previous.copy(sentBytes = previous.totalBytes, status = "Hoàn tất"))
        }
        json.optString("blobId").takeIf(String::isNotBlank)?.let(outgoingPayloads::remove)
        scope.launch {
            delay(1_500)
            transferProgress.value = transferProgress.value.filterNot { it.transferId == transferId }
        }
    }

    private fun setTransferProgress(progress: TransferProgress) {
        transferProgress.value = (transferProgress.value.filterNot { it.transferId == progress.transferId } + progress)
            .takeLast(4)
    }

    private data class BinaryChunk(
        val transferId: String,
        val blobId: String,
        val offset: Long,
        val total: Long,
        val hash: ByteArray,
        val windowSize: Int,
        val data: ByteArray
    )

    private fun encodeBinaryChunk(
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
            .put(BINARY_MAGIC)
            .put(if (eof) 1 else 0)
            .put(transfer.size.toByte())
            .put(blob.size.toByte())
            .put(windowSize.coerceIn(1, MAX_WINDOW_SIZE).toByte())
            .putLong(offset)
            .putLong(total)
            .put(hash)
            .put(transfer)
            .put(blob)
            .put(data)
            .array()
    }

    private fun decodeBinaryChunk(frame: ByteArray): BinaryChunk? {
        if (frame.size < 56 || !frame.copyOfRange(0, 4).contentEquals(BINARY_MAGIC)) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.position(4)
        buffer.get() // flags
        val transferLength = buffer.get().toInt() and 0xff
        val blobLength = buffer.get().toInt() and 0xff
        val windowSize = (buffer.get().toInt() and 0xff).coerceIn(1, MAX_WINDOW_SIZE)
        val offset = buffer.long
        val total = buffer.long
        val hash = ByteArray(32).also(buffer::get)
        if (56 + transferLength + blobLength > frame.size || total !in 0..MAX_TRANSFER_JSON_BYTES) return null
        val transfer = ByteArray(transferLength).also(buffer::get).toString(Charsets.UTF_8)
        val blob = ByteArray(blobLength).also(buffer::get).toString(Charsets.UTF_8)
        val data = ByteArray(buffer.remaining()).also(buffer::get)
        return BinaryChunk(transfer, blob, offset, total, hash, windowSize, data)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun saveToHistory(payload: ClipboardPayload, source: String) {
        scope.launch {
            try {
                historyRepository.mergeEntry(
                    content = payload.text,
                    source = source,
                    promoteExisting = true,
                    payload = payload
                )
            } catch (e: Exception) {
                Log.e(TAG, "Save history failed: ${e.message}")
            }
        }
    }

    private fun sendWire(message: String): Boolean {
        if (wsClient?.isSecure == true) return wsClient?.send(message) == true
        val protected = runCatching { encryptionStore.protect(message) }
            .getOrElse { error ->
                connectionEvents.tryEmit("Không mã hoá được dữ liệu: ${error.message ?: "thiếu khoá"}")
                return false
            }
        return wsClient?.send(protected) == true
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FastPasteApp.CHANNEL_ID)
            .setContentTitle("Fast Paste")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        stopBackgroundDiscovery()
        activeTarget.value = null
        connectionState.value = ConnectionState.DISCONNECTED
        clientScope?.cancel()
        wsClient?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClipboardService"
        private const val NOTIFICATION_ID = 1
        private const val MAX_HISTORY_ITEMS = 1_000
        private const val TRANSFER_CHUNK_BYTES = 48 * 1024L
        private const val DEFAULT_WINDOW_SIZE = 4
        private const val MAX_WINDOW_SIZE = 8
        private const val MAX_TRANSFER_JSON_BYTES = 96L * 1024 * 1024
        private val BINARY_MAGIC = "FPB3".toByteArray(Charsets.US_ASCII)
        private const val ACK_TIMEOUT_MS = 2_500L
        private const val MAX_TRANSFER_RETRIES = 5
        const val ACTION_START = "com.fastpaste.START"
        const val ACTION_STOP = "com.fastpaste.STOP"
        const val ACTION_COPY_HISTORY_ITEM = "com.fastpaste.COPY_HISTORY_ITEM"
        const val ACTION_SYNC_CURRENT_CLIP = "com.fastpaste.SYNC_CURRENT_CLIP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_ENTRY_ID = "entryId"
        val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val transferProgress = MutableStateFlow<List<TransferProgress>>(emptyList())

        /** "host:port" the service is currently managing, null when idle. */
        val activeTarget = MutableStateFlow<String?>(null)
    }
}
