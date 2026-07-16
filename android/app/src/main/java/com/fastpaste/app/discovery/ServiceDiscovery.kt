package com.fastpaste.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

class ServiceDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private var listenJob: Job? = null
    @Volatile
    private var activeSocket: DatagramSocket? = null
    // Bumped on every start/stop; a scan window whose generation is stale must
    // not touch shared state (activeSocket, _isScanning) owned by a newer scan.
    @Volatile
    private var scanGeneration = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * With [cycle] the discovery keeps scanning in windows of [SCAN_TIMEOUT_MS]
     * separated by growing pauses (5s doubling up to 60s — cheap on battery
     * when the PC stays offline) until [stopDiscovery] is called.
     */
    fun startDiscovery(cycle: Boolean = false) {
        if (listenJob?.isActive == true) return
        _servers.value = emptyList()
        val generation = ++scanGeneration

        listenJob = scope.launch {
            var pauseMs = INITIAL_RESCAN_PAUSE_MS
            while (isActive && generation == scanGeneration) {
                val found = scanWindow(generation)
                if (!cycle || !isActive || generation != scanGeneration) break
                pauseMs = if (found) {
                    INITIAL_RESCAN_PAUSE_MS
                } else {
                    minOf(pauseMs * 2, MAX_RESCAN_PAUSE_MS)
                }
                delay(pauseMs)
            }
        }
    }

    fun stopDiscovery() {
        scanGeneration++
        // Closing the socket unblocks a pending receive() immediately
        try {
            activeSocket?.close()
        } catch (_: Exception) { /* ignored */ }
        activeSocket = null
        listenJob?.cancel()
        listenJob = null
        _isScanning.value = false
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.createMulticastLock("FastPaste_UDP").apply {
            setReferenceCounted(true)
            acquire()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to acquire MulticastLock: ${e.message}")
        null
    }

    /** Runs one scan window. Returns true if at least one server was seen. */
    private fun CoroutineScope.scanWindow(generation: Int): Boolean {
        var found = false
        // Lock and socket are per-window locals, so a stale window can only
        // ever release its own lock and close its own socket.
        val multicastLock = acquireMulticastLock()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                // receive() blocks and coroutine cancellation can't interrupt
                // it — poll with a short timeout so the scan window deadline
                // takes effect even without traffic.
                soTimeout = RECEIVE_POLL_MS
                bind(InetSocketAddress(4568))
                broadcast = true
            }
            activeSocket = socket
            _isScanning.value = true

            val buffer = ByteArray(1024)
            Log.d(TAG, "Listening for UDP broadcasts on port 4568")
            val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS

            while (isActive && generation == scanGeneration &&
                System.currentTimeMillis() < deadline
            ) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }

                val message = String(packet.data, 0, packet.length)
                if (message.startsWith("FASTPASTE:")) {
                    val parts = message.split(":")
                    if (parts.size >= 3) {
                        val hostname = parts[1]
                        val port = parts[2].toIntOrNull() ?: 4567
                        val host = packet.address.hostAddress ?: continue

                        val server = DiscoveredServer(hostname, host, port)
                        Log.d(TAG, "Discovered via UDP: $server")

                        updateServer(server)
                        found = true
                    }
                }
            }
            Log.d(TAG, "UDP discovery scan window ended")
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "UDP listen error: ${e.message}")
            }
        } finally {
            socket?.close()
            // Only clear shared state if this window is still the current scan
            if (generation == scanGeneration) {
                activeSocket = null
                _isScanning.value = false
            }
            try {
                multicastLock?.release()
            } catch (e: Exception) { /* ignored */ }
        }
        return found
    }

    private fun updateServer(server: DiscoveredServer) {
        val cutoff = System.currentTimeMillis() - SERVER_TTL_MS
        val currentList = _servers.value
            .filter { it.lastSeen >= cutoff }
            .filter { it.host != server.host }
            .filterNot { it.name == server.name && it.port == server.port }

        _servers.value = (currentList + server)
            .sortedByDescending { it.lastSeen }
    }

    companion object {
        private const val TAG = "ServiceDiscovery"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val SERVER_TTL_MS = 30_000L
        private const val RECEIVE_POLL_MS = 2_000
        private const val INITIAL_RESCAN_PAUSE_MS = 5_000L
        private const val MAX_RESCAN_PAUSE_MS = 60_000L
    }
}
