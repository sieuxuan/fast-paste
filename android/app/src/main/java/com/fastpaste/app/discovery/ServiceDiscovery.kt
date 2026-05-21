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

    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startDiscovery() {
        if (listenJob?.isActive == true) return

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

                // Auto-stop discovery after 10 seconds to save battery
                withTimeoutOrNull(10000L) {
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

                                val currentList = _servers.value.filter { it.host != host }
                                _servers.value = currentList + server
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

        // Release multicast lock
        try {
            multicastLock?.release()
            multicastLock = null
            Log.d(TAG, "MulticastLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Release lock error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ServiceDiscovery"
    }
}
