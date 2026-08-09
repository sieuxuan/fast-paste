package com.fastpaste.app.websocket

import android.content.Context
import android.util.Log
import com.fastpaste.app.security.SecureChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

class WebSocketClient(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

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

    private val _binaryMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    val binaryMessages: SharedFlow<ByteArray> = _binaryMessages

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events

    private var serverUrl: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var secureChannel: SecureChannel? = null
    val isSecure: Boolean get() = secureChannel?.isSecure == true
    val remoteSyncCursor: Long get() = secureChannel?.remoteSyncCursor ?: 0L
    @Volatile
    private var shouldReconnect = false

    fun connect(host: String, port: Int) {
        disconnect()
        serverUrl = "ws://$host:$port"
        secureChannel = SecureChannel(appContext, host)
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
                reconnectAttempt = 0
                val awaitingSecurity = runCatching {
                    secureChannel?.start(webSocket::send) == true
                }.getOrElse { error ->
                    _events.tryEmit("Ghép đôi lỗi: ${error.message ?: "không rõ"}")
                    webSocket.close(4003, "Secure handshake failed")
                    return
                }
                if (awaitingSecurity) {
                    _events.tryEmit(
                        if (secureChannel?.requiresPairing == true) {
                            "PC yêu cầu ghép đôi: mở Cài đặt và quét QR trên PC"
                        } else {
                            "Đang xác thực thiết bị và tạo session key mới"
                        }
                    )
                    _state.value = ConnectionState.CONNECTING
                } else {
                    _events.tryEmit("Kết nối legacy chưa ghép đôi")
                    _state.value = ConnectionState.CONNECTED
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: ${text.take(60)}")
                val event = runCatching {
                    secureChannel?.handleIncoming(text, webSocket::send)
                }.getOrElse { error ->
                    _events.tryEmit("Xác thực session lỗi: ${error.message ?: "không rõ"}")
                    webSocket.close(4003, "Secure authentication failed")
                    return
                }
                if (event == null) {
                    _messages.tryEmit(text)
                    return
                }
                event.status?.let(_events::tryEmit)
                if (event.connected) {
                    _state.value = ConnectionState.CONNECTED
                }
                event.message?.let(_messages::tryEmit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val plain = runCatching { secureChannel?.unprotectBinary(bytes.toByteArray()) }
                    .getOrElse { error ->
                        _events.tryEmit("Xác thực binary lỗi: ${error.message ?: "không rõ"}")
                        webSocket.close(4003, "Secure binary authentication failed")
                        return
                    }
                if (plain != null) _binaryMessages.tryEmit(plain)
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

    fun send(text: String): Boolean {
        val channel = secureChannel ?: return false
        if (!channel.isSecure) return false
        val protected = channel.protect(text)
        return webSocket?.send(protected) == true
    }

    fun sendBinary(frame: ByteArray): Boolean {
        val channel = secureChannel ?: return false
        if (!channel.isSecure) return false
        val protected = channel.protectBinary(frame) ?: return false
        return webSocket?.send(ByteString.of(*protected)) == true
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
        secureChannel?.close()
        secureChannel = null
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
