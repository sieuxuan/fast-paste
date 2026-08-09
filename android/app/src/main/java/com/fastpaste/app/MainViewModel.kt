package com.fastpaste.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastpaste.app.cloud.GoogleDriveCloudSync
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.discovery.ServiceDiscovery
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.data.ClipboardPayload
import com.fastpaste.app.data.ClipboardRepository
import com.fastpaste.app.service.ClipboardService
import com.fastpaste.app.service.TransferProgress
import com.fastpaste.app.security.PairingStore
import com.fastpaste.app.sync.DeletedHistoryStore
import com.fastpaste.app.sync.EncryptionStore
import com.fastpaste.app.sync.HistoryBackupStore
import com.fastpaste.app.websocket.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ConnectionLogEntry(
    val time: String,
    val message: String
)

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
    val updateMessage: String = "FastPaste ${BuildConfig.VERSION_NAME}",
    val cloudSyncing: Boolean = false,
    val cloudSignedIn: Boolean = false,
    val cloudMessage: String = "Đăng nhập Google để bật tự đồng bộ",
    val lastSyncText: String = "Chưa đồng bộ",
    val deletedBackupCount: Int = 0,
    val e2eeEnabled: Boolean = false,
    val e2eeKeyId: String = "",
    val e2eeMessage: String = "Chưa bật mã hoá đầu cuối",
    val pairingMessage: String = "Chưa ghép đôi với PC",
    val pairedDeviceCount: Int = 0,
    val transfers: List<TransferProgress> = emptyList(),
    val connectionLogs: List<ConnectionLogEntry> = listOf(
        ConnectionLogEntry("Bây giờ", "Đang khởi động và tìm PC cùng mạng.")
    )
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FastPasteApp
    private val discovery = ServiceDiscovery(application)
    private val dao = app.database.clipboardDao()
    private val historyRepository = ClipboardRepository(dao)
    private val updateClient = OkHttpClient()
    private val googleDriveCloudSync = GoogleDriveCloudSync()
    private val deletedHistoryStore = DeletedHistoryStore(application)
    private val historyBackupStore = HistoryBackupStore(application)
    private val encryptionStore = EncryptionStore(application)
    private val pairingStore = PairingStore(application)
    private val cloudPrefs =
        application.getSharedPreferences("fastpaste_cloud", Context.MODE_PRIVATE)
    private var autoConnectEnabled = true
    private val loggedServers = mutableSetOf<String>()
    private var lastConnectRequest: Pair<String, Long>? = null
    private var lastDeletedBatch: List<ClipboardEntry> = historyBackupStore.load()
    private val cloudSyncInFlight = AtomicBoolean(false)
    @Volatile private var lastCloudAccessToken: String? = null

    private val _uiState = MutableStateFlow(
        UiState(
            deletedBackupCount = lastDeletedBatch.size,
            e2eeEnabled = encryptionStore.isEnabled,
            e2eeKeyId = encryptionStore.keyId,
            e2eeMessage = if (encryptionStore.isEnabled) {
                "Bản sao Google Drive đang được mã hoá"
            } else {
                "Chưa bật mã hoá đầu cuối"
            },
            pairingMessage = if (pairingStore.peers().isEmpty()) {
                "Quét QR trên FastPaste PC để tạo khoá riêng"
            } else {
                "Kết nối dùng session key mới mỗi lần reconnect"
            },
            pairedDeviceCount = pairingStore.peers().size
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        discovery.startDiscovery(cycle = true)
        checkForUpdates()
        if (isCloudSyncEnabled()) {
            _uiState.update {
                it.copy(
                    cloudSignedIn = true,
                    cloudMessage = "Tự đồng bộ Google Drive đang bật"
                )
            }
        }

        viewModelScope.launch {
            discovery.servers.collect { servers ->
                _uiState.update { it.copy(discoveredServers = servers) }
                servers.forEach { server ->
                    val key = "${server.host}:${server.port}"
                    if (loggedServers.add(key)) {
                        addConnectionLog("Tìm thấy PC ${server.name} tại $key")
                    }
                    if (server.desktopId.isNotBlank()) {
                        pairingStore.peerForDesktopId(server.desktopId)?.let { peer ->
                            if (peer.host != server.host || peer.port != server.port) {
                                pairingStore.rebind(server.desktopId, server.host, server.port)
                                addConnectionLog("PC đã ghép đôi đổi IP sang $key; giữ nguyên khoá thiết bị")
                            }
                        }
                    }
                }

                // Auto-connect to the first discovered server. Gate on the
                // service's real state (not the optimistic uiState), skip
                // targets the service is already managing (its own retry loop
                // owns those — reconnecting here would reset its backoff), and
                // throttle repeat requests for the same target.
                if (autoConnectEnabled && servers.isNotEmpty()) {
                    val server = servers.firstOrNull { discovered ->
                        pairingStore.pendingForHost(discovered.host) != null ||
                            pairingStore.peerForHost(discovered.host) != null ||
                            discovered.desktopId.isNotBlank() &&
                            pairingStore.peerForDesktopId(discovered.desktopId) != null
                    }
                    if (server == null) {
                        _uiState.update {
                            it.copy(connectionMessage = "Đã tìm thấy PC. Quét QR để ghép đôi an toàn.")
                        }
                        return@collect
                    }
                    val target = "${server.host}:${server.port}"
                    val now = System.currentTimeMillis()
                    val recentlyRequested = lastConnectRequest?.let { (requested, at) ->
                        requested == target && now - at < CONNECT_REQUEST_THROTTLE_MS
                    } == true
                    if (
                        ClipboardService.connectionState.value == ConnectionState.DISCONNECTED &&
                        ClipboardService.activeTarget.value != target &&
                        !recentlyRequested
                    ) {
                        connectToServer(server.host, server.port)
                    }
                }
            }
        }

        viewModelScope.launch {
            // Rescan cycles live inside ServiceDiscovery; this collector only
            // mirrors the flag for the UI.
            discovery.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }

        viewModelScope.launch {
            dao.getRecent(MAX_HISTORY_ITEMS)
                .distinctUntilChanged()
                .collect { history ->
                    _uiState.update { it.copy(clipboardHistory = history) }
                }
        }

        viewModelScope.launch {
            ClipboardService.connectionState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        connectionMessage = when (state) {
                            ConnectionState.CONNECTED_SECURE -> "Đồng bộ clipboard đang hoạt động"
                            ConnectionState.CONNECTED_UNPAIRED -> "Đã tìm thấy PC nhưng chưa ghép đôi. Quét QR trên PC để bật đồng bộ."
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
                if (state == ConnectionState.CONNECTED_SECURE || state == ConnectionState.CONNECTED_UNPAIRED) {
                    discovery.stopDiscovery()
                } else if (state == ConnectionState.DISCONNECTED && autoConnectEnabled) {
                    discovery.startDiscovery(cycle = true)
                }
            }
        }

        viewModelScope.launch {
            ClipboardService.connectionEvents.collect { event ->
                addConnectionLog(event)
                if (event.contains("ghép đôi", ignoreCase = true) ||
                    event.contains("forward secrecy", ignoreCase = true)
                ) {
                    val count = pairingStore.peers().size
                    _uiState.update {
                        it.copy(
                            pairedDeviceCount = count,
                            pairingMessage = if (count > 0) {
                                "Đã xác thực thiết bị; session key tự đổi khi reconnect"
                            } else it.pairingMessage
                        )
                    }
                }
                if (event.contains("đồng bộ", ignoreCase = true)) {
                    _uiState.update { it.copy(lastSyncText = "Vừa xong") }
                }
            }
        }
    }

    fun connectToServer(host: String, port: Int) {
        autoConnectEnabled = true
        lastConnectRequest = "$host:$port" to System.currentTimeMillis()
        val intent = Intent(getApplication(), ClipboardService::class.java).apply {
            action = ClipboardService.ACTION_START
            putExtra(ClipboardService.EXTRA_HOST, host)
            putExtra(ClipboardService.EXTRA_PORT, port)
        }

        viewModelScope.launch {
            ClipboardService.transferProgress.collect { transfers ->
                _uiState.update { it.copy(transfers = transfers) }
            }
        }
        try {
            getApplication<FastPasteApp>().startForegroundService(intent)
        } catch (e: Exception) {
            // Android 12+ blocks foreground-service starts while the app is in
            // the background — don't crash, just report and stay disconnected.
            addConnectionLog("Không thể khởi động dịch vụ nền: ${e.message ?: "bị hệ thống chặn"}")
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    connectionMessage = "Mở lại ứng dụng để kết nối."
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                connectedServer = "$host:$port",
                connectionState = ConnectionState.CONNECTING,
                connectionMessage = "Đang kết nối tới $host:$port"
            )
        }
        addConnectionLog("Bắt đầu kết nối tới $host:$port")
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
        addConnectionLog("Đã ngắt kết nối theo yêu cầu.")
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
        discovery.startDiscovery(cycle = true)
        _uiState.update {
            it.copy(
                discoveredServers = emptyList(),
                connectionMessage = "Đang quét PC cùng mạng Wi-Fi"
            )
        }
        addConnectionLog("Quét lại PC cùng mạng.")
    }

    fun connectManual() {
        val state = _uiState.value
        val port = state.manualPort.toIntOrNull() ?: 4567
        if (state.manualIp.isNotBlank()) {
            connectToServer(state.manualIp, port)
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            dao.getById(id)?.let { entry ->
                deletedHistoryStore.markDeleted(entry.content, includePinned = true)
            }
            dao.deleteById(id)
        }
    }

    fun toggleHistoryPin(id: Long) {
        viewModelScope.launch {
            val entry = dao.getById(id) ?: return@launch
            dao.updatePinned(id, !entry.pinned)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val removable = dao.getAllOnce().filterNot { it.pinned }
            if (removable.isEmpty()) return@launch
            lastDeletedBatch = removable
            historyBackupStore.save(removable)
            deletedHistoryStore.markCleared(removable)
            dao.clearUnpinned()
            _uiState.update { it.copy(deletedBackupCount = removable.size) }
        }
    }

    fun installPairingQr(uri: String) {
        runCatching { pairingStore.installInvitation(uri) }
            .onSuccess { invitation ->
                _uiState.update {
                    it.copy(
                        pairingMessage = "Đã đọc QR; đang xác thực ${invitation.host}",
                        connectedServer = "${invitation.host}:${invitation.port}"
                    )
                }
                addConnectionLog("Đã đọc QR ghép đôi; tạo khoá riêng cho PC ${invitation.host}")
                connectToServer(invitation.host, invitation.port)
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(pairingMessage = "QR không hợp lệ: ${error.message ?: "không rõ"}")
                }
            }
    }

    fun deleteHistoryItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val idSet = ids.toSet()
            val removable = dao.getAllOnce().filter { !it.pinned && it.id in idSet }
            if (removable.isEmpty()) return@launch
            lastDeletedBatch = removable
            historyBackupStore.save(removable)
            deletedHistoryStore.markDeletedBatch(
                texts = removable.map { it.content },
                includePinned = false
            )
            dao.deleteUnpinnedByIds(removable.map { it.id })
            _uiState.update { it.copy(deletedBackupCount = removable.size) }
        }
    }

    fun undoHistoryDelete() {
        viewModelScope.launch {
            val backup = lastDeletedBatch.ifEmpty { historyBackupStore.load() }
            if (backup.isEmpty()) return@launch
            backup.forEach { entry ->
                deletedHistoryStore.unmarkDeleted(entry.content)
                historyRepository.mergeEntry(
                    content = entry.content,
                    source = entry.source,
                    sourceApp = entry.sourceApp,
                    sourceTitle = entry.sourceTitle,
                    sourceIcon = entry.sourceIcon,
                    timestamp = entry.timestamp,
                    pinned = entry.pinned,
                    folder = entry.folder,
                    payload = ClipboardPayload.fromEntry(entry)
                )
            }
            lastDeletedBatch = emptyList()
            historyBackupStore.clear()
            _uiState.update { it.copy(deletedBackupCount = 0) }
        }
    }

    fun updateHistoryItem(id: Long, content: String, folder: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            val entry = dao.getById(id) ?: return@launch
            val cleanContent = content
            if (entry.content != cleanContent) {
                deletedHistoryStore.markDeleted(entry.content, includePinned = true)
                deletedHistoryStore.unmarkDeleted(cleanContent)
            }
            dao.updateEditedEntry(
                id = id,
                content = cleanContent,
                folder = ClipboardRepository.cleanFolderName(folder),
                timestamp = System.currentTimeMillis()
            )
            dao.deleteDuplicatesByContent(cleanContent, id)
            val intent = Intent(getApplication(), ClipboardService::class.java).apply {
                action = ClipboardService.ACTION_COPY_HISTORY_ITEM
                putExtra(ClipboardService.EXTRA_ENTRY_ID, id)
            }
            getApplication<FastPasteApp>().startService(intent)
        }
    }

    fun copyHistoryItem(id: Long) {
        viewModelScope.launch {
            val entry = dao.getById(id)
            if (entry != null && !entry.blobReady && entry.blobId.isNotBlank()) {
                val token = lastCloudAccessToken
                if (!token.isNullOrBlank()) {
                    runCatching {
                        googleDriveCloudSync.downloadBlob(token, entry.blobId, encryptionStore)
                    }.getOrNull()?.let { payload ->
                        historyRepository.mergeEntry(
                            content = entry.content,
                            source = entry.source,
                            sourceApp = entry.sourceApp,
                            sourceTitle = entry.sourceTitle,
                            sourceIcon = entry.sourceIcon,
                            timestamp = entry.timestamp,
                            pinned = entry.pinned,
                            folder = entry.folder,
                            promoteExisting = true,
                            payload = payload,
                            blobId = entry.blobId,
                            blobSize = payload.encodedSize(),
                            blobReady = true
                        )
                    }
                }
            }
            val intent = Intent(getApplication(), ClipboardService::class.java).apply {
                action = ClipboardService.ACTION_COPY_HISTORY_ITEM
                putExtra(ClipboardService.EXTRA_ENTRY_ID, id)
            }
            getApplication<FastPasteApp>().startService(intent)
        }
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

    fun setCloudMessage(message: String) {
        _uiState.update { it.copy(cloudMessage = message, cloudSyncing = false) }
    }

    fun setE2eePassphrase(passphrase: String) {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { encryptionStore.setPassphrase(passphrase) }
                .onSuccess { keyId ->
                    _uiState.update {
                        it.copy(
                            e2eeEnabled = true,
                            e2eeKeyId = keyId,
                            e2eeMessage = "Đã bật E2EE; khoá $keyId"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(e2eeMessage = error.message ?: "Không thiết lập được E2EE")
                    }
                }
        }
    }

    fun setE2eeEnabled(enabled: Boolean) {
        runCatching { encryptionStore.setEnabled(enabled) }
            .onSuccess {
                _uiState.update {
                    it.copy(
                        e2eeEnabled = enabled,
                        e2eeKeyId = encryptionStore.keyId,
                        e2eeMessage = if (enabled) {
                            "Bản sao Google Drive đang được mã hoá"
                        } else {
                            "Đã tắt E2EE; lần sync Drive tới sẽ chuyển về plaintext"
                        }
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(e2eeMessage = error.message ?: "Không đổi được E2EE") }
            }
    }

    fun isCloudSyncEnabled(): Boolean {
        return cloudPrefs.getBoolean(CLOUD_SYNC_ENABLED, false)
    }

    fun syncGoogleDrive(accessToken: String?, rememberLogin: Boolean = true) {
        if (accessToken.isNullOrBlank()) {
            setCloudMessage("Không lấy được quyền Google Drive.")
            return
        }
        lastCloudAccessToken = accessToken
        if (!cloudSyncInFlight.compareAndSet(false, true)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cloudSyncing = true,
                    cloudMessage = "Đang đồng bộ Google Drive..."
                )
            }

            runCatching {
                val localEntries = dao.getRecentOnce(MAX_HISTORY_ITEMS)
                val result = googleDriveCloudSync.merge(
                    accessToken = accessToken,
                    localEntries = localEntries,
                    encryptionStore = encryptionStore,
                    isDeleted = deletedHistoryStore::isDeleted
                )
                var inserted = 0
                result.entriesToMerge.forEach { entry ->
                    if (!deletedHistoryStore.isDeleted(entry.content, entry.timestamp, entry.pinned)) {
                        val mergeResult = historyRepository.mergeEntry(
                            content = entry.content,
                            source = entry.source,
                            sourceApp = entry.sourceApp,
                            sourceTitle = entry.sourceTitle,
                            sourceIcon = entry.sourceIcon,
                            timestamp = entry.timestamp,
                            pinned = entry.pinned,
                            folder = entry.folder,
                            payload = ClipboardPayload.fromEntry(entry),
                            blobId = entry.blobId,
                            blobSize = entry.blobSize,
                            blobReady = entry.blobReady
                        )
                        if (mergeResult.inserted) {
                            inserted++
                        }
                    }
                }
                inserted to result.mergedCount
            }
            .onSuccess { (inserted, mergedCount) ->
                cloudSyncInFlight.set(false)
                if (rememberLogin) {
                    cloudPrefs.edit().putBoolean(CLOUD_SYNC_ENABLED, true).apply()
                }
                val syncTime = formatClock()
                _uiState.update {
                    it.copy(
                        cloudSyncing = false,
                        cloudSignedIn = true,
                        cloudMessage = "Tự đồng bộ Google Drive: $mergedCount mục, tải về $inserted mục mới",
                        lastSyncText = syncTime
                    )
                }
                addConnectionLog("Google Drive sync lúc $syncTime: $mergedCount mục, tải về $inserted mục mới.")
            }
            .onFailure { error ->
                cloudSyncInFlight.set(false)
                _uiState.update {
                    it.copy(
                        cloudSyncing = false,
                        cloudMessage = "Đồng bộ Google lỗi: ${error.message ?: "không rõ"}"
                    )
                }
                addConnectionLog("Google Drive sync lỗi: ${error.message ?: "không rõ"}")
            }
        }
    }

    private fun addConnectionLog(message: String) {
        _uiState.update { state ->
            val next = listOf(ConnectionLogEntry(formatClock(), message)) + state.connectionLogs
            state.copy(connectionLogs = next.take(MAX_CONNECTION_LOGS))
        }
    }

    private fun formatClock(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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
        private const val MAX_HISTORY_ITEMS = 1_000
        private const val MAX_CONNECTION_LOGS = 12
        private const val CONNECT_REQUEST_THROTTLE_MS = 10_000L
        private const val CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
    }
}
