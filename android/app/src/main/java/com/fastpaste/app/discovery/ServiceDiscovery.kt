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
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startDiscovery() {
        if (listenJob?.isActive == true) return
        _servers.value = emptyList()
        _isScanning.value = true

        // Acquire multicast lock — required for receiving broadcast on many Android devices
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("FastPaste_UDP").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }

        listenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(4568))
                socket.broadcast = true

                val buffer = ByteArray(1024)
                Log.d(TAG, "Listening for UDP broadcasts on port 4568")

                // Auto-stop discovery after a short scan window to save battery
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    while (isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)

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
                            }
                        }
                    }
                }
                Log.d(TAG, "UDP discovery timeout reached")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "UDP listen error: ${e.message}")
                }
            } finally {
                socket?.close()
                _isScanning.value = false
                Log.d(TAG, "UDP socket closed")
                
                // Release lock when timeout is reached
                try {
                    multicastLock?.release()
                    multicastLock = null
                } catch (e: Exception) { /* ignored */ }
            }
        }
    }

    fun stopDiscovery() {
        listenJob?.cancel()
        listenJob = null
        _isScanning.value = false

        // Release multicast lock
        try {
            multicastLock?.release()
            multicastLock = null
            Log.d(TAG, "MulticastLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Release lock error: ${e.message}")
        }
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
    }
}
