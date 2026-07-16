package com.fastpaste.app.websocket

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class WebSocketClient(private val scope: CoroutineScope) {

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // Keep-alive
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(10, java.util.concurrent.TimeUnit.SECONDS) // Detect dead connections
        .build()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events

    private var serverUrl: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    @Volatile
    private var shouldReconnect = false

    fun connect(host: String, port: Int) {
        disconnect()
        serverUrl = "ws://$host:$port"
        shouldReconnect = true
        _state.value = ConnectionState.CONNECTING
        doConnect()
    }

    private fun doConnect() {
        val url = serverUrl ?: return
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
                _events.tryEmit("Đã mở WebSocket tới $url")
                _state.value = ConnectionState.CONNECTED
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: ${text.take(60)}")
                _messages.tryEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $reason")
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failed: ${t.message}")
                _events.tryEmit("Kết nối lỗi: ${t.message ?: "không rõ"}")
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect(url)
            }
        })
    }

    fun send(text: String) {
        webSocket?.send(text)
    }

    /** Skip the current backoff delay and retry immediately (no-op unless idle). */
    fun retryNow() {
        if (!shouldReconnect || _state.value != ConnectionState.DISCONNECTED) return
        reconnectJob?.cancel()
        _state.value = ConnectionState.CONNECTING
        doConnect()
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        serverUrl = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
        reconnectAttempt = 0
    }

    private fun scheduleReconnect(failedUrl: String) {
        if (!shouldReconnect || serverUrl != failedUrl) return
        reconnectJob?.cancel()

        // Never give up on our own: the PC may just be asleep or restarting.
        // Back off exponentially and keep retrying until disconnect() is called
        // or a newer connection replaces this one.
        reconnectJob = scope.launch {
            val delayMs = minOf(350L * (1 shl minOf(reconnectAttempt, 6)), MAX_RECONNECT_DELAY_MS)
            val nextAttempt = reconnectAttempt + 1
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $nextAttempt)")
            // Log the first few attempts, then sample — endless retries must
            // not flood the short connection log.
            if (nextAttempt <= 3 || nextAttempt % 10 == 0) {
                _events.tryEmit("Thử kết nối lại lần $nextAttempt sau ${delayMs / 1000.0}s")
            }
            delay(delayMs)
            if (!shouldReconnect || serverUrl != failedUrl) return@launch
            reconnectAttempt = nextAttempt
            _state.value = ConnectionState.CONNECTING
            doConnect()
        }
    }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val MAX_RECONNECT_DELAY_MS = 15_000L
    }
}
