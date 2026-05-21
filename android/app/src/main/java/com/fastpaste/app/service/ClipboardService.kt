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
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.websocket.ConnectionState
import com.fastpaste.app.websocket.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

class ClipboardService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager
    private var wsClient: WebSocketClient? = null
    private var lastSyncedText = ""

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
                startSync(host, port)
            }
            ACTION_STOP -> {
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
        val notification = buildNotification("Connecting to $host:$port...")
        startForeground(NOTIFICATION_ID, notification)

        wsClient?.disconnect()
        wsClient = WebSocketClient(scope).also { client ->
            client.connect(host, port)

            // Receive clipboard from desktop
            scope.launch {
                client.messages.collect { text ->
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
            }

            // Update notification with connection status
            scope.launch {
                client.state.collectLatest { state ->
                    connectionState.value = state
                    val status = when (state) {
                        ConnectionState.CONNECTED -> {
                            // Auto-sync local clipboard when reconnected
                            try {
                                val currentText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                                if (currentText != null && currentText.isNotEmpty() && currentText != lastSyncedText) {
                                    lastSyncedText = currentText
                                    client.send(currentText)
                                    saveToHistory(currentText, "LOCAL")
                                    Log.d(TAG, "Auto-synced clipboard after reconnect")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Auto-sync failed: ${e.message}")
                            }
                            "Connected to $host"
                        }
                        ConnectionState.CONNECTING -> "Connecting to $host..."
                        ConnectionState.DISCONNECTED -> "Disconnected — retrying..."
                    }
                    updateNotification(status)
                }
            }
        }

        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    private fun saveToHistory(text: String, source: String) {
        scope.launch {
            try {
                val db = (application as FastPasteApp).database
                db.clipboardDao().insert(ClipboardEntry(content = text, source = source))
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
        wsClient?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClipboardService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.fastpaste.START"
        const val ACTION_STOP = "com.fastpaste.STOP"
        const val ACTION_SEND_TEXT = "com.fastpaste.SEND_TEXT"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_TEXT = "text"
        val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    }
}
