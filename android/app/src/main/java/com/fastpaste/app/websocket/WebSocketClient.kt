package com.fastpaste.app.websocket

import android.content.Context
import android.util.Log
import com.fastpaste.app.security.SecureChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_UNPAIRED,
    CONNECTED_SECURE
}

internal fun protocolMessageType(text: String): String =
    runCatching { JSONObject(text).optString("type") }.getOrDefault("")

internal class ConnectionGeneration {
    private var value = 0L
    fun advance(): Long = ++value
    fun isCurrent(generation: Long): Boolean = generation == value
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

    // A socket has one service consumer. Queue messages until it starts;
    // SharedFlow without replay silently loses the initial history exchange.
    private val _messages = Channel<String>(Channel.UNLIMITED)
    val messages = _messages.receiveAsFlow()

    private val _binaryMessages = Channel<ByteArray>(Channel.UNLIMITED)
    val binaryMessages = _binaryMessages.receiveAsFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events

    private var serverUrl: String? = null
    private var serverHost: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var secureChannel: SecureChannel? = null
    private val connections = ConnectionGeneration()
    val isSecure: Boolean get() = synchronized(this) { secureChannel?.isSecure == true }
    val remoteSyncCursor: Long get() = synchronized(this) { secureChannel?.remoteSyncCursor ?: 0L }
    @Volatile
    private var shouldReconnect = false

    @Synchronized
    fun connect(host: String, port: Int) {
        disconnect()
        serverUrl = "ws://$host:$port"
        serverHost = host
        shouldReconnect = true
        publishState(ConnectionState.CONNECTING)
        doConnect()
    }

    private fun doConnect() {
        val url = serverUrl ?: return
        val generation = connections.advance()
        // Reload the persisted peer/cursor on every retry. A completed pairing
        // may have changed both since the previous socket was opened.
        secureChannel?.close()
        secureChannel = SecureChannel(appContext, serverHost ?: return)
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                Log.d(TAG, "Connected to $url")
                _events.tryEmit("Đã mở WebSocket tới $url")
                reconnectAttempt = 0
                val awaitingSecurity = runCatching {
                    secureChannel?.start(webSocket::send) == true
                }.getOrElse { error ->
                    _events.tryEmit("Ghép đôi lỗi: ${error.message ?: "không rõ"}")
                    webSocket.close(4003, "Secure handshake failed")
                    return@synchronized
                }
                if (awaitingSecurity) {
                    _events.tryEmit(
                        if (secureChannel?.requiresPairing == true) {
                            "PC yêu cầu ghép đôi: mở Cài đặt và quét QR trên PC"
                        } else {
                            "Đang xác thực thiết bị và tạo session key mới"
                        }
                    )
                    publishState(if (secureChannel?.requiresPairing == true) {
                        ConnectionState.CONNECTED_UNPAIRED
                    } else {
                        ConnectionState.CONNECTING
                    })
                } else {
                    _events.tryEmit("Kết nối chưa ghép đôi; chưa có dữ liệu nào được đồng bộ")
                    publishState(ConnectionState.CONNECTED_UNPAIRED)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                if (protocolMessageType(text) == "pair_required") {
                    _events.tryEmit("PC chưa ghép đôi thiết bị này. Quét QR trên PC để bật đồng bộ.")
                    publishState(ConnectionState.CONNECTED_UNPAIRED)
                    return@synchronized
                }
                val event = runCatching {
                    secureChannel?.handleIncoming(text, webSocket::send)
                }.getOrElse { error ->
                    _events.tryEmit("Xác thực session lỗi: ${error.message ?: "không rõ"}")
                    webSocket.close(4003, "Secure authentication failed")
                    return@synchronized
                }
                if (event == null) {
                    return@synchronized
                }
                event.status?.let(_events::tryEmit)
                if (event.connected) {
                    publishState(ConnectionState.CONNECTED_SECURE)
                }
                event.message?.let { _messages.trySend(it) }
                Unit
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                val plain = runCatching { secureChannel?.unprotectBinary(bytes.toByteArray()) }
                    .getOrElse { error ->
                        _events.tryEmit("Xác thực binary lỗi: ${error.message ?: "không rõ"}")
                        webSocket.close(4003, "Secure binary authentication failed")
                        return@synchronized
                    }
                if (plain != null) _binaryMessages.trySend(plain)
                Unit
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                webSocket.close(1000, null)
                Unit
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                Log.d(TAG, "Closed: $reason")
                secureChannel?.close()
                publishState(ConnectionState.DISCONNECTED)
                scheduleReconnect(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(this@WebSocketClient) {
                if (!connections.isCurrent(generation) || !shouldReconnect) return@synchronized
                Log.e(TAG, "Failed: ${t.message}")
                secureChannel?.close()
                _events.tryEmit("Kết nối lỗi: ${t.message ?: "không rõ"}")
                publishState(ConnectionState.DISCONNECTED)
                scheduleReconnect(url)
            }
        })
    }

    // Encryption and enqueue must be one operation: concurrent clipboard,
    // history and ACK sends share the same AES-GCM nonce sequence.
    @Synchronized
    fun send(text: String): Boolean {
        val channel = secureChannel ?: return false
        if (!channel.isSecure) return false
        val protected = channel.protect(text)
        return webSocket?.send(protected) == true
    }

    @Synchronized
    fun sendBinary(frame: ByteArray): Boolean {
        val channel = secureChannel ?: return false
        if (!channel.isSecure) return false
        val protected = channel.protectBinary(frame) ?: return false
        return webSocket?.send(ByteString.of(*protected)) == true
    }

    /** Skip the current backoff delay and retry immediately (no-op unless idle). */
    @Synchronized
    fun retryNow() {
        if (!shouldReconnect || _state.value != ConnectionState.DISCONNECTED) return
        reconnectJob?.cancel()
        publishState(ConnectionState.CONNECTING)
        doConnect()
    }

    @Synchronized
    fun disconnect() {
        connections.advance()
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        serverUrl = null
        serverHost = null
        secureChannel?.close()
        secureChannel = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        publishState(ConnectionState.DISCONNECTED)
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
            synchronized(this@WebSocketClient) {
                if (!shouldReconnect || serverUrl != failedUrl) return@synchronized
                reconnectAttempt = nextAttempt
                publishState(ConnectionState.CONNECTING)
                doConnect()
            }
        }
    }

    private fun publishState(state: ConnectionState) {
        _state.value = state
    }

    companion object {
        private const val TAG = "WebSocketClient"
        private const val MAX_RECONNECT_DELAY_MS = 15_000L
    }
}
