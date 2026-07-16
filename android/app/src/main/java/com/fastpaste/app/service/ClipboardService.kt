package com.fastpaste.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fastpaste.app.FastPasteApp
import com.fastpaste.app.MainActivity
import com.fastpaste.app.R
import com.fastpaste.app.data.ClipboardRepository
import com.fastpaste.app.discovery.ServiceDiscovery
import com.fastpaste.app.sync.DeletedHistoryStore
import com.fastpaste.app.websocket.ConnectionState
import com.fastpaste.app.websocket.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject

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
    private var lastSyncedText = ""
    private val dao by lazy { (application as FastPasteApp).database.clipboardDao() }
    private val historyRepository by lazy { ClipboardRepository(dao) }
    private val deletedHistoryStore by lazy { DeletedHistoryStore(applicationContext) }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
        if (text.isNotEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            wsClient?.send(text)
            saveToHistory(text, "LOCAL")
            Log.d(TAG, "Sent: ${text.take(60)}")
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
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                if (text.isNotEmpty() && text != lastSyncedText) {
                    lastSyncedText = text
                    wsClient?.send(text)
                    saveToHistory(text, "LOCAL")
                    Log.d(TAG, "Sent via intent: ${text.take(60)}")
                }
            }
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
        wsClient = WebSocketClient(cs).also { client ->
            client.connect(host, port)

            // Receive clipboard from desktop
            cs.launch {
                client.messages.collect { message ->
                    handleIncomingMessage(message)
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
                    val server = servers.firstOrNull() ?: return@collect
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
        val handledAsSync = try {
            val json = JSONObject(message)
            if (json.optString("app") == "fastpaste" && json.optString("type") == "history_sync") {
                mergeHistorySync(json.optJSONArray("entries") ?: JSONArray())
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        if (!handledAsSync) {
            receiveClipboardText(message)
        }
    }

    private suspend fun receiveClipboardText(text: String) {
        if (text.isNotEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Fast Paste", text)
                )
            }
            saveToHistory(text, "REMOTE")
            Log.d(TAG, "Received: ${text.take(60)}")
        }
    }

    private fun sendHistorySync(client: WebSocketClient) {
        scope.launch {
            try {
                val entries = dao.getRecentOnce(MAX_HISTORY_ITEMS)
                val seen = mutableSetOf<String>()
                val history = JSONArray()
                entries.forEach { entry ->
                    if (deletedHistoryStore.isDeleted(entry.content, entry.timestamp)) {
                        return@forEach
                    }
                    seen.add(entry.content)
                    history.put(JSONObject()
                        .put("text", entry.content)
                        .put("timestamp", entry.timestamp)
                        .put("source", if (entry.source == "REMOTE") "PC" else "ANDROID")
                        .put("sourceApp", entry.sourceApp)
                        .put("sourceTitle", entry.sourceTitle)
                        .put("pinned", entry.pinned)
                        .put("folder", entry.folder)
                    )
                }

                val currentText = clipboardManager.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                if (
                    !currentText.isNullOrBlank() &&
                    !seen.contains(currentText) &&
                    !deletedHistoryStore.hasMarker(currentText)
                ) {
                    val timestamp = System.currentTimeMillis()
                    historyRepository.mergeEntry(
                        content = currentText,
                        source = "LOCAL",
                        timestamp = timestamp
                    )
                    history.put(JSONObject()
                        .put("text", currentText)
                        .put("timestamp", timestamp)
                        .put("source", "ANDROID")
                        .put("sourceApp", "")
                        .put("sourceTitle", "")
                        .put("pinned", false)
                        .put("folder", "")
                    )
                }

                val payload = JSONObject()
                    .put("app", "fastpaste")
                    .put("type", "history_sync")
                    .put("entries", history)
                client.send(payload.toString())
                connectionEvents.tryEmit("Đã gửi ${history.length()} mục lịch sử sang PC")
            } catch (e: Exception) {
                Log.e(TAG, "History sync send failed: ${e.message}")
                connectionEvents.tryEmit("Gửi lịch sử lỗi: ${e.message ?: "không rõ"}")
            }
        }
    }

    private suspend fun mergeHistorySync(entries: JSONArray) {
        val latestLocalTimestamp = dao.getLatestOnce()?.timestamp ?: 0L
        var newestIncomingText: String? = null
        var newestIncomingTimestamp = 0L
        var inserted = 0

        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue

            val timestamp = item.optLong("timestamp", System.currentTimeMillis())
            if (deletedHistoryStore.isDeleted(text, timestamp)) continue

            val source = if (item.optString("source") == "ANDROID") "LOCAL" else "REMOTE"
            val sourceApp = item.optString("sourceApp", item.optString("source_app", ""))
            val sourceTitle = item.optString("sourceTitle", item.optString("source_title", ""))
            val pinned = item.optBoolean("pinned", false)
            val folder = ClipboardRepository.cleanFolderName(item.optString("folder", ""))
            if (timestamp > newestIncomingTimestamp) {
                newestIncomingTimestamp = timestamp
                newestIncomingText = text
            }

            val mergeResult = historyRepository.mergeEntry(
                content = text,
                source = source,
                sourceApp = sourceApp,
                sourceTitle = sourceTitle,
                timestamp = timestamp,
                pinned = pinned,
                folder = folder
            )
            if (mergeResult.inserted) {
                inserted++
            }
        }

        val textToApply = newestIncomingText
        if (newestIncomingTimestamp > latestLocalTimestamp && !textToApply.isNullOrBlank()) {
            lastSyncedText = textToApply
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Fast Paste", textToApply)
                )
            }
        }

        Log.d(TAG, "Merged history sync: $inserted new items")
        connectionEvents.tryEmit("Đã nhận đồng bộ từ PC: thêm $inserted mục mới")
    }

    private fun saveToHistory(text: String, source: String) {
        scope.launch {
            try {
                historyRepository.mergeEntry(
                    content = text,
                    source = source,
                    promoteExisting = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Save history failed: ${e.message}")
            }
        }
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
        private const val MAX_HISTORY_ITEMS = 500
        const val ACTION_START = "com.fastpaste.START"
        const val ACTION_STOP = "com.fastpaste.STOP"
        const val ACTION_SEND_TEXT = "com.fastpaste.SEND_TEXT"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TEXT = "text"
        val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

        /** "host:port" the service is currently managing, null when idle. */
        val activeTarget = MutableStateFlow<String?>(null)
    }
}
