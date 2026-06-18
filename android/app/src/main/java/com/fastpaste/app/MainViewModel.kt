package com.fastpaste.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.discovery.ServiceDiscovery
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.service.ClipboardService
import com.fastpaste.app.websocket.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val connectedServer: String? = null,
    val discoveredServers: List<DiscoveredServer> = emptyList(),
    val isScanning: Boolean = false,
    val clipboardHistory: List<ClipboardEntry> = emptyList(),
    val manualIp: String = "",
    val manualPort: String = "4567",
    val connectionMessage: String = "Đang tìm PC cùng mạng Wi-Fi",
    val checkingUpdate: Boolean = false,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val updateUrl: String? = null,
    val updateMessage: String = "FastPaste ${BuildConfig.VERSION_NAME}"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FastPasteApp
    private val discovery = ServiceDiscovery(application)
    private val dao = app.database.clipboardDao()
    private val updateClient = OkHttpClient()
    private var autoConnectEnabled = true

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        discovery.startDiscovery()
        checkForUpdates()

        viewModelScope.launch {
            discovery.servers.collect { servers ->
                _uiState.update { it.copy(discoveredServers = servers) }

                // Auto-connect to first discovered server if currently disconnected
                if (
                    autoConnectEnabled &&
                    servers.isNotEmpty() &&
                    _uiState.value.connectionState == ConnectionState.DISCONNECTED
                ) {
                    val server = servers.first()
                    connectToServer(server.host, server.port)
                }
            }
        }

        viewModelScope.launch {
            discovery.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }

        viewModelScope.launch {
            dao.getRecent(200).collect { history ->
                _uiState.update { it.copy(clipboardHistory = history) }
            }
        }

        viewModelScope.launch {
            ClipboardService.connectionState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        connectionMessage = when (state) {
                            ConnectionState.CONNECTED -> "Đồng bộ clipboard đang hoạt động"
                            ConnectionState.CONNECTING -> "Đang kết nối tới ${it.connectedServer ?: "PC"}"
                            ConnectionState.DISCONNECTED -> if (autoConnectEnabled) {
                                "Chưa kết nối. Ứng dụng đang quét PC cùng mạng."
                            } else {
                                "Đã ngắt kết nối. Bấm quét lại hoặc nhập IP để kết nối."
                            }
                        }
                    )
                }

                // Battery Optimization: Stop broadcasting/listening when connected
                if (state == ConnectionState.CONNECTED) {
                    discovery.stopDiscovery()
                } else if (state == ConnectionState.DISCONNECTED && autoConnectEnabled) {
                    discovery.startDiscovery()
                }
            }
        }
    }

    fun connectToServer(host: String, port: Int) {
        autoConnectEnabled = true
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
        _uiState.update {
            it.copy(
                connectedServer = "$host:$port",
                connectionState = ConnectionState.CONNECTING,
                connectionMessage = "Đang kết nối tới $host:$port"
            )
        }
    }

    fun disconnectFromServer() {
        autoConnectEnabled = false
        discovery.stopDiscovery()
        val intent = Intent(getApplication(), ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_STOP
        }
        getApplication<FastPasteApp>().startService(intent)
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                connectedServer = null,
                connectionMessage = "Đã ngắt kết nối. Bấm quét lại hoặc nhập IP để kết nối."
            )
        }
    }

    fun updateManualIp(ip: String) {
        _uiState.update { it.copy(manualIp = ip) }
    }

    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    fun restartDiscovery() {
        autoConnectEnabled = true
        discovery.stopDiscovery()
        discovery.startDiscovery()
        _uiState.update {
            it.copy(
                discoveredServers = emptyList(),
                connectionMessage = "Đang quét PC cùng mạng Wi-Fi"
            )
        }
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

    fun checkForUpdates() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    checkingUpdate = true,
                    updateMessage = "Đang kiểm tra cập nhật..."
                )
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url("$UPDATE_MANIFEST_URL?t=${System.currentTimeMillis()}")
                        .build()

                    updateClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        JSONObject(response.body?.string().orEmpty())
                    }
                }
            }

            result
                .onSuccess { manifest ->
                    val android = manifest.optJSONObject("android")
                    val latestCode = android?.optInt("versionCode", 0) ?: 0
                    val latestName = android?.optString("versionName").orEmpty()
                    val apkUrl = android?.optString("apkUrl").orEmpty()
                    val releaseUrl = manifest.optString("releaseUrl", FALLBACK_RELEASE_URL)
                    val hasUpdate = latestCode > BuildConfig.VERSION_CODE

                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            updateAvailable = hasUpdate,
                            latestVersion = latestName.ifBlank { null },
                            updateUrl = apkUrl.ifBlank { releaseUrl },
                            updateMessage = if (hasUpdate) {
                                "Có bản mới ${latestName.ifBlank { latestCode.toString() }} trên GitHub"
                            } else {
                                "Đang dùng bản mới nhất (${BuildConfig.VERSION_NAME})"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            updateMessage = "Không kiểm tra được cập nhật: ${error.message ?: "lỗi mạng"}"
                        )
                    }
                }
        }
    }

    fun openUpdatePage() {
        val url = _uiState.value.updateUrl ?: FALLBACK_RELEASE_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<FastPasteApp>().startActivity(intent)
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        super.onCleared()
    }

    companion object {
        private const val UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/sieuxuan/fast-paste/master/update.json"
        private const val FALLBACK_RELEASE_URL =
            "https://github.com/sieuxuan/fast-paste/releases/latest"
    }
}
