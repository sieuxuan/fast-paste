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

    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal lateinit var clipboardManager: ClipboardManager
    internal var wsClient: WebSocketClient? = null
    // Per-client scope: cancelling it tears down the client's collectors AND
    // its pending reconnect jobs in one shot.
    private var clientScope: CoroutineScope? = null
    internal var currentHost: String? = null
    private var currentPort = 0
    // Service-owned discovery keeps running in the background (the ViewModel's
    // discovery dies with the UI), so a PC coming back on a new IP is found.
    private var backgroundDiscovery: ServiceDiscovery? = null
    internal val fingerprintGate = ClipboardFingerprintGate()
    internal val dao by lazy { (application as FastPasteApp).database.clipboardDao() }
    internal val historyRepository by lazy { ClipboardRepository(dao) }
    internal val deletedHistoryStore by lazy { DeletedHistoryStore(applicationContext) }
    internal val encryptionStore by lazy { EncryptionStore(applicationContext) }
    internal val pairingStore by lazy { PairingStore(applicationContext) }
    private val servicePrefs by lazy { getSharedPreferences("fastpaste_service", Context.MODE_PRIVATE) }
    internal val transferStore by lazy { EncryptedTransferStore(applicationContext) }
    internal val outgoingPayloads = ConcurrentHashMap<String, ClipboardPayload>()
    internal val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()

    internal data class IncomingTransfer(
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
            if (!fingerprintGate.shouldSend(fingerprint)) return@launch
            sendClipboardPayload(payload)
            saveToHistory(payload, "LOCAL")
            Log.d(TAG, "Sent ${payload.kind}: ${payload.text.take(60)}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ensureBackgroundDiscovery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                startForeground(NOTIFICATION_ID, buildNotification("Đang khôi phục kết nối…"))
                ensureBackgroundDiscovery()
                if (currentHost == null) {
                    servicePrefs.getString(KEY_ACTIVE_DESKTOP_ID, null)
                        ?.let(pairingStore::peerForDesktopId)
                        ?.let { peer -> startSync(peer.host, peer.port) }
                }
            }
            ACTION_START_DISCOVERY -> {
                startForeground(NOTIFICATION_ID, buildNotification("Đang tìm PC cùng mạng…"))
                ensureBackgroundDiscovery()
            }
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
                servicePrefs.edit().remove(KEY_ACTIVE_DESKTOP_ID).apply()
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
        pairingStore.peerForHost(host)?.let { peer ->
            servicePrefs.edit().putString(KEY_ACTIVE_DESKTOP_ID, peer.desktopId).apply()
        }
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
                        ConnectionState.CONNECTED_SECURE -> {
                            pairingStore.peerForHost(host)?.let { peer ->
                                servicePrefs.edit().putString(KEY_ACTIVE_DESKTOP_ID, peer.desktopId).apply()
                            }
                            stopBackgroundDiscovery()
                            sendHistorySync(client)
                            Log.d(TAG, "Connected; exchanging clipboard history")
                            connectionEvents.tryEmit("Đã kết nối tới $host:$port")
                            "Đã kết nối tới $host"
                        }
                        ConnectionState.CONNECTED_UNPAIRED -> {
                            stopBackgroundDiscovery()
                            "Chưa ghép đôi với $host — quét QR trên PC"
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
                    discoveredServers.value = servers
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
                        connectionState.value != ConnectionState.CONNECTED_SECURE
                    ) {
                        Log.d(TAG, "PC reappeared at new address $target — switching")
                        connectionEvents.tryEmit("Tìm thấy PC ở địa chỉ mới $target, đang chuyển kết nối")
                        startSync(server.host, server.port)
                    }
                }
            }
            scope.launch { d.isScanning.collect { isScanning.value = it } }
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

    internal suspend fun receiveClipboardPayload(payload: ClipboardPayload) {
        if (payload.text.isEmpty() || !payload.isWithinLimit()) return
        val fingerprint = payload.fingerprint()
        if (!fingerprintGate.shouldApply(fingerprint)) return
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

        fingerprintGate.markAppliedAndSent(payload.fingerprint())
        withContext(Dispatchers.Main) {
            clipboardManager.setPrimaryClip(clip)
        }

        val sent = sendClipboardPayload(payload)
        val label = when (payload.kind) {
            ClipboardPayload.KIND_IMAGE -> "ảnh"
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

    internal fun sendWire(message: String): Boolean {
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
        internal const val TAG = "ClipboardService"
        private const val NOTIFICATION_ID = 1
        internal const val MAX_HISTORY_ITEMS = 1_000
        internal const val TRANSFER_CHUNK_BYTES = 48 * 1024L
        internal const val DEFAULT_WINDOW_SIZE = 4
        internal const val MAX_WINDOW_SIZE = 8
        internal const val MAX_TRANSFER_JSON_BYTES = 96L * 1024 * 1024
        internal val BINARY_MAGIC = "FPB3".toByteArray(Charsets.US_ASCII)
        internal const val ACK_TIMEOUT_MS = 2_500L
        internal const val MAX_TRANSFER_RETRIES = 5
        private const val KEY_ACTIVE_DESKTOP_ID = "activeDesktopId"
        const val ACTION_START = "com.fastpaste.START"
        const val ACTION_STOP = "com.fastpaste.STOP"
        const val ACTION_COPY_HISTORY_ITEM = "com.fastpaste.COPY_HISTORY_ITEM"
        const val ACTION_SYNC_CURRENT_CLIP = "com.fastpaste.SYNC_CURRENT_CLIP"
        const val ACTION_START_DISCOVERY = "com.fastpaste.START_DISCOVERY"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_ENTRY_ID = "entryId"
        val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val transferProgress = MutableStateFlow<List<TransferProgress>>(emptyList())
        val discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
        val isScanning = MutableStateFlow(false)

        /** "host:port" the service is currently managing, null when idle. */
        val activeTarget = MutableStateFlow<String?>(null)
    }
}
