package com.fastpaste.app

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.discovery.ServiceDiscovery
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.service.ClipboardService
import com.fastpaste.app.websocket.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedServer: String? = null,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val clipboardHistory: List<ClipboardEntry> = emptyList(),
    val manualIp: String = "",
    val manualPort: String = "4567"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FastPasteApp
    private val discovery = ServiceDiscovery(application)
    private val dao = app.database.clipboardDao()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        discovery.startDiscovery()

        viewModelScope.launch {
            discovery.servers.collect { servers ->
                _uiState.update { it.copy(discoveredServers = servers) }
                
                // Auto-connect to first discovered server if currently disconnected
                if (servers.isNotEmpty() && _uiState.value.connectionState == ConnectionState.DISCONNECTED) {
                    val server = servers.first()
                    connectToServer(server.host, server.port)
                }
            }
        }

        viewModelScope.launch {
            dao.getRecent(200).collect { history ->
                _uiState.update { it.copy(clipboardHistory = history) }
            }
        }

        viewModelScope.launch {
            ClipboardService.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                
                // Battery Optimization: Stop broadcasting/listening when connected
                if (state == ConnectionState.CONNECTED) {
                    discovery.stopDiscovery()
                } else if (state == ConnectionState.DISCONNECTED) {
                    discovery.startDiscovery()
                }
            }
        }
    }

    fun connectToServer(host: String, port: Int) {
        val intent = Intent(getApplication(), ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_START
            putExtra(ClipboardService.EXTRA_HOST, host)
            putExtra(ClipboardService.EXTRA_PORT, port)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplication<FastPasteApp>().startForegroundService(intent)
        } else {
            getApplication<FastPasteApp>().startService(intent)
        }
        _uiState.update { it.copy(connectedServer = "$host:$port") }
    }

    fun disconnectFromServer() {
        val intent = Intent(getApplication(), ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_STOP
        }
        getApplication<FastPasteApp>().startService(intent)
        _uiState.update {
            it.copy(connectionState = ConnectionState.DISCONNECTED, connectedServer = null)
        }
    }

    fun updateManualIp(ip: String) {
        _uiState.update { it.copy(manualIp = ip) }
    }

    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    fun restartDiscovery() {
        discovery.stopDiscovery()
        discovery.startDiscovery()
    }

    fun connectManual() {
        val state = _uiState.value
        val port = state.manualPort.toIntOrNull() ?: 4567
        if (state.manualIp.isNotBlank()) {
            connectToServer(state.manualIp, port)
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { dao.deleteById(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clearAll() }
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        super.onCleared()
    }
}
