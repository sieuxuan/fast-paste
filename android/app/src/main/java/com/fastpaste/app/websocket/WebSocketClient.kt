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

    private var serverUrl: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    fun connect(host: String, port: Int) {
        disconnect()
        serverUrl = "ws://$host:$port"
        _state.value = ConnectionState.CONNECTING
        doConnect()
    }

    private fun doConnect() {
        val url = serverUrl ?: return
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url")
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
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failed: ${t.message}")
                _state.value = ConnectionState.DISCONNECTED
                scheduleReconnect()
            }
        })
    }

    fun send(text: String) {
        webSocket?.send(text)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        serverUrl = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _state.value = ConnectionState.DISCONNECTED
        reconnectAttempt = 0
    }

    private fun scheduleReconnect() {
        if (serverUrl == null) return
        reconnectJob?.cancel()

        if (reconnectAttempt >= 5) {
            Log.d(TAG, "Max reconnect attempts reached. Giving up.")
            disconnect()
            return
        }

        reconnectJob = scope.launch {
            val delayMs = minOf(350L * (1 shl minOf(reconnectAttempt, 5)), 8_000L)
            Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
            delay(delayMs)
            reconnectAttempt++
            _state.value = ConnectionState.CONNECTING
            doConnect()
        }
    }

    companion object {
        private const val TAG = "WebSocketClient"
    }
}
