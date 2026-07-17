package com.fastpaste.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastpaste.app.UiState
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.ui.theme.GreenConnected
import com.fastpaste.app.ui.theme.LocalBadge
import com.fastpaste.app.ui.theme.OrangeConnecting
import com.fastpaste.app.ui.theme.RedDisconnected
import com.fastpaste.app.ui.theme.RemoteBadge
import com.fastpaste.app.websocket.ConnectionState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onConnectServer: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onManualIpChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDeleteItems: (List<Long>) -> Unit,
    onTogglePin: (Long) -> Unit,
    onClearHistory: () -> Unit,
    onUndoHistoryDelete: () -> Unit,
    onCopyItem: (Long) -> Unit,
    onEditItem: (Long, String, String) -> Unit,
    onRefreshDiscovery: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onGoogleSync: () -> Unit = {}
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var historyFilter by rememberSaveable { mutableStateOf(HISTORY_FILTER_ALL) }
    var pendingDelete by remember { mutableStateOf<PendingHistoryDelete?>(null) }
    var editingEntry by remember { mutableStateOf<ClipboardEntry?>(null) }
    val historyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val showScrollToTop by remember {
        derivedStateOf {
            historyListState.firstVisibleItemIndex > 3 || historyListState.firstVisibleItemScrollOffset > 900
        }
    }
    val folders = remember(state.clipboardHistory) {
        state.clipboardHistory
            .map { it.folder.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val sourceApps = remember(state.clipboardHistory) {
        state.clipboardHistory
            .map { it.sourceApp.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val visibleHistory = remember(state.clipboardHistory, searchQuery, historyFilter) {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            state.clipboardHistory
        } else {
            state.clipboardHistory.filter { entry ->
                entry.content.contains(query, ignoreCase = true) ||
                    entry.folder.contains(query, ignoreCase = true) ||
                    entry.sourceApp.contains(query, ignoreCase = true) ||
                    entry.sourceTitle.contains(query, ignoreCase = true)
            }
        }
        filtered.filter { entry ->
            when {
                historyFilter == HISTORY_FILTER_PINNED -> entry.pinned
                historyFilter == HISTORY_FILTER_UNTAGGED -> entry.folder.isBlank()
                historyFilter.startsWith(HISTORY_FILTER_FOLDER_PREFIX) ->
                    entry.folder == historyFilter.removePrefix(HISTORY_FILTER_FOLDER_PREFIX)
                historyFilter.startsWith(HISTORY_FILTER_APP_PREFIX) ->
                    entry.sourceApp == historyFilter.removePrefix(HISTORY_FILTER_APP_PREFIX)
                else -> true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (settingsOpen) {
                        IconButton(onClick = { settingsOpen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "FP",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(if (settingsOpen) "Cài đặt" else "FastPaste", fontWeight = FontWeight.Bold)
                            Text(
                                text = if (settingsOpen) "Kết nối, đồng bộ và cập nhật" else state.connectionMessage,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                },
                actions = {
                    if (!settingsOpen) {
                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Cài đặt")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = !settingsOpen && showScrollToTop) {
                SmallFloatingActionButton(
                    onClick = { coroutineScope.launch { historyListState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Lên đầu lịch sử")
                }
            }
        }
    ) { padding ->
        if (settingsOpen) {
            SettingsSheet(
                state = state,
                onConnectServer = onConnectServer,
                onDisconnect = onDisconnect,
                onManualIpChange = onManualIpChange,
                onManualPortChange = onManualPortChange,
                onConnectManual = onConnectManual,
                onRefreshDiscovery = onRefreshDiscovery,
                onCheckUpdate = onCheckUpdate,
                onOpenUpdate = onOpenUpdate,
                onGoogleSync = onGoogleSync,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            state = historyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HistoryHeader(
                    count = state.clipboardHistory.size,
                    filteredCount = visibleHistory.count { !it.pinned },
                    isFiltered = historyFilter != HISTORY_FILTER_ALL || searchQuery.isNotBlank(),
                    onClearHistory = {
                        val ids = state.clipboardHistory.filterNot { it.pinned }.map { it.id }
                        if (ids.isNotEmpty()) pendingDelete = PendingHistoryDelete(ids, deleteAll = true)
                    },
                    onDeleteFiltered = {
                        val ids = visibleHistory.filterNot { it.pinned }.map { it.id }
                        if (ids.isNotEmpty()) pendingDelete = PendingHistoryDelete(ids, deleteAll = false)
                    }
                )
            }

            if (state.deletedBackupCount > 0) {
                item {
                    UndoHistoryBanner(
                        count = state.deletedBackupCount,
                        onUndo = onUndoHistoryDelete
                    )
                }
            }

            item {
                HistorySearch(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it }
                )
            }

            item {
                HistoryFilters(
                    history = state.clipboardHistory,
                    folders = folders,
                    sourceApps = sourceApps,
                    selected = historyFilter,
                    onSelect = { historyFilter = it }
                )
            }

            item {
                StatusStrip(state = state, onOpenMenu = { settingsOpen = true })
            }

            if (state.clipboardHistory.isEmpty()) {
                item { EmptyHistory() }
            } else if (visibleHistory.isEmpty()) {
                item { EmptySearch() }
            } else {
                historyGroups(visibleHistory).forEach { (dateLabel, entries) ->
                    item {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    items(entries, key = { it.id }) { entry ->
                        HistoryItem(
                            entry = entry,
                            onCopy = { onCopyItem(entry.id) },
                            onTogglePin = { onTogglePin(entry.id) },
                            onEdit = { editingEntry = entry },
                            onDelete = { onDeleteItem(entry.id) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    pendingDelete?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(if (request.deleteAll) "Xoá lịch sử" else "Xoá theo bộ lọc") },
            text = {
                Text(
                    "Xoá ${request.ids.size} mục chưa ghim? Các mục đã ghim luôn được giữ lại. " +
                        "Bạn có thể hoàn tác ngay sau khi xoá."
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (request.deleteAll) onClearHistory() else onDeleteItems(request.ids)
                    pendingDelete = null
                }) { Text("Xoá") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Huỷ") }
            }
        )
    }

    editingEntry?.let { entry ->
        EditHistoryDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { content, folder ->
                onEditItem(entry.id, content, folder)
                editingEntry = null
            }
        )
    }
}

@Composable
private fun SettingsSheet(
    state: UiState,
    onConnectServer: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onManualIpChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit,
    onGoogleSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsHeader(state = state)

        SettingSectionTitle("01 · Kết nối PC", "Tự tìm PC trong LAN hoặc nhập IP thủ công.")
        ConnectionPanel(
            state = state,
            onConnectServer = onConnectServer,
            onDisconnect = onDisconnect,
            onManualIpChange = onManualIpChange,
            onManualPortChange = onManualPortChange,
            onConnectManual = onConnectManual,
            onRefreshDiscovery = onRefreshDiscovery
        )

        SettingSectionTitle("02 · Đồng bộ đám mây", "Lưu lịch sử riêng trong Google Drive app data.")
        CloudSyncPanel(
            state = state,
            onGoogleSync = onGoogleSync
        )

        SettingSectionTitle("03 · Cập nhật", "Kiểm tra bản Android mới trên GitHub.")
        UpdatePanel(
            state = state,
            onCheckUpdate = onCheckUpdate,
            onOpenUpdate = onOpenUpdate
        )

        SettingSectionTitle("04 · Nhật ký gần đây", "Theo dõi discover, reconnect và lần sync mới nhất.")
        ConnectionLogPanel(state = state)

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsHeader(state: UiState) {
    val connection = connectionUi(state.connectionState)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("FP", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("FastPaste Control", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        state.connectionMessage,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPill(
                    label = connection.title,
                    value = state.connectedServer ?: "LAN",
                    color = connection.color,
                    modifier = Modifier.weight(1f)
                )
                SummaryPill(
                    label = "Lịch sử",
                    value = "${state.clipboardHistory.size} mục",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                SummaryPill(
                    label = "Sync",
                    value = state.lastSyncText,
                    color = if (state.cloudSignedIn) GreenConnected else OrangeConnecting,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun SettingSectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
    }
}

@Composable
private fun CloudSyncPanel(
    state: UiState,
    onGoogleSync: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Google Drive", fontWeight = FontWeight.Bold)
                    Text(
                        state.cloudMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                    Text(
                        "Lần sync: ${state.lastSyncText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                    )
                }
            }

            if (state.cloudSyncing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Button(
                onClick = onGoogleSync,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.cloudSyncing
            ) {
                Text(if (state.cloudSignedIn) "Đồng bộ ngay" else "Đăng nhập Google")
            }
        }
    }
}

@Composable
private fun UpdatePanel(
    state: UiState,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Phiên bản", fontWeight = FontWeight.Bold)
                    Text(
                        state.updateMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                OutlinedButton(onClick = onCheckUpdate, enabled = !state.checkingUpdate) {
                    Text(if (state.checkingUpdate) "Đang kiểm tra" else "Kiểm tra")
                }
            }

            if (state.checkingUpdate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.updateAvailable) {
                Button(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) {
                    Text("Tải bản mới từ GitHub")
                }
            }
        }
    }
}

@Composable
private fun ConnectionLogPanel(state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.connectionLogs.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        entry.time,
                        modifier = Modifier.width(58.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        entry.message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: UiState,
    onConnectServer: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
    onManualIpChange: (String) -> Unit,
    onManualPortChange: (String) -> Unit,
    onConnectManual: () -> Unit,
    onRefreshDiscovery: () -> Unit
) {
    val status = connectionUi(state.connectionState)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(status.container),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(status.icon, contentDescription = null, tint = status.color)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = state.connectedServer ?: state.connectionMessage,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                if (state.connectionState == ConnectionState.CONNECTED) {
                    FilledTonalButton(onClick = onDisconnect) {
                        Text("Ngắt")
                    }
                } else {
                    IconButton(onClick = onRefreshDiscovery) {
                        Icon(Icons.Default.Refresh, contentDescription = "Quét lại")
                    }
                }
            }

            if (state.connectionState == ConnectionState.CONNECTING || state.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.connectionState == ConnectionState.DISCONNECTED) {
                DiscoveredServers(
                    servers = state.discoveredServers,
                    isScanning = state.isScanning,
                    onConnectServer = onConnectServer,
                    onRefreshDiscovery = onRefreshDiscovery
                )

                ManualConnect(
                    ip = state.manualIp,
                    port = state.manualPort,
                    onIpChange = onManualIpChange,
                    onPortChange = onManualPortChange,
                    onConnect = onConnectManual
                )
            }
        }
    }
}

@Composable
private fun DiscoveredServers(
    servers: List<DiscoveredServer>,
    isScanning: Boolean,
    onConnectServer: (String, Int) -> Unit,
    onRefreshDiscovery: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PC trong mạng",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRefreshDiscovery) {
                Text(if (isScanning) "Đang quét" else "Quét lại")
            }
        }

        if (servers.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = if (isScanning) {
                        "Đang tìm FastPaste trên PC cùng Wi-Fi..."
                    } else {
                        "Chưa tìm thấy PC. Kiểm tra PC đang mở FastPaste và cùng mạng Wi-Fi."
                    },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        } else {
            servers.forEach { server ->
                ServerRow(
                    server = server,
                    onClick = { onConnectServer(server.host, server.port) }
                )
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
            Button(onClick = onClick) {
                Text("Kết nối")
            }
        }
    }
}

@Composable
private fun ManualConnect(
    ip: String,
    port: String,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nhập IP thủ công", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("IP PC") },
                placeholder = { Text("192.168.0.41") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        OutlinedButton(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = ip.isNotBlank()
        ) {
            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Kết nối bằng IP")
        }
    }
}

@Composable
private fun HistoryHeader(
    count: Int,
    filteredCount: Int,
    isFiltered: Boolean,
    onClearHistory: () -> Unit,
    onDeleteFiltered: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Lịch sử clipboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Các mục mới nhất",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if ((!isFiltered && count > 0) || (isFiltered && filteredCount > 0)) {
            TextButton(onClick = if (isFiltered) onDeleteFiltered else onClearHistory) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isFiltered) "Xoá lọc ($filteredCount)" else "Xoá")
            }
        }
    }
}

@Composable
private fun UndoHistoryBanner(count: Int, onUndo: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Đã sao lưu $count mục vừa xoá", fontWeight = FontWeight.Bold)
                Text(
                    "Mục ghim không bị ảnh hưởng.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                )
            }
            TextButton(onClick = onUndo) { Text("Hoàn tác") }
        }
    }
}

@Composable
private fun HistorySearch(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        placeholder = { Text("Tìm trong lịch sử clipboard") },
        singleLine = true,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun HistoryFilters(
    history: List<ClipboardEntry>,
    folders: List<String>,
    sourceApps: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val filters = remember(history, folders, sourceApps) {
        listOf(
            HistoryFilterOption(HISTORY_FILTER_ALL, "Tất cả"),
            HistoryFilterOption(HISTORY_FILTER_PINNED, "Ghim"),
            HistoryFilterOption(HISTORY_FILTER_UNTAGGED, "Chưa nhãn")
        ) + folders.map { folder ->
            HistoryFilterOption("$HISTORY_FILTER_FOLDER_PREFIX$folder", folder)
        } + sourceApps.map { app ->
            HistoryFilterOption("$HISTORY_FILTER_APP_PREFIX$app", app)
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters, key = { it.key }) { filter ->
            FilterChip(
                option = filter,
                selected = selected == filter.key,
                onClick = { onSelect(filter.key) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    option: HistoryFilterOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                option.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusStrip(
    state: UiState,
    onOpenMenu: () -> Unit
) {
    val connection = connectionUi(state.connectionState)
    val cloudText = when {
        state.cloudSyncing -> "Google đang đồng bộ"
        state.cloudSignedIn -> "Google tự đồng bộ"
        else -> "Google chưa đăng nhập"
    }
    val cloudColor = if (state.cloudSignedIn || state.cloudSyncing) GreenConnected else RedDisconnected

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactChip(
            text = connection.title,
            color = connection.color,
            modifier = Modifier.weight(1f),
            onClick = onOpenMenu
        )
        CompactChip(
            text = cloudText,
            color = cloudColor,
            modifier = Modifier.weight(1f),
            onClick = onOpenMenu
        )
    }
}

@Composable
private fun CompactChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.ContentPaste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Chưa có dữ liệu copy", fontWeight = FontWeight.SemiBold)
            Text(
                "Sao chép trên điện thoại hoặc PC để bắt đầu đồng bộ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun EmptySearch() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Không tìm thấy nội dung", fontWeight = FontWeight.SemiBold)
            Text(
                "Thử từ khóa ngắn hơn hoặc kiểm tra lại dấu tiếng Việt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun EditHistoryDialog(
    entry: ClipboardEntry,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var folder by remember(entry.id) { mutableStateOf(entry.folder) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
        title = { Text("Sửa clipboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Nội dung") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    minLines = 5
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Nhãn") },
                    placeholder = { Text("Công việc, Code, Cá nhân…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "${content.length} ký tự",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(content, folder) }, enabled = content.isNotBlank()) {
                Text("Lưu")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

@Composable
private fun HistoryItem(
    entry: ClipboardEntry,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isLocal = entry.source == "LOCAL"
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceBadge(
                        text = if (isLocal) "Điện thoại" else "PC",
                        color = if (isLocal) LocalBadge else RemoteBadge
                    )
                    if (entry.payloadType != "text") {
                        SourceBadge(
                            text = when (entry.payloadType) {
                                "image" -> "Ảnh"
                                "files" -> "File"
                                "html" -> "Rich text"
                                else -> entry.payloadType
                            },
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (entry.pinned) {
                        SourceBadge(
                            text = "Ghim",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (entry.folder.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = entry.folder,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (entry.sourceApp.isNotBlank()) {
                        SourceBadge(
                            text = entry.sourceApp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text(
                        timeFormat.format(Date(entry.timestamp)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                if (entry.sourceTitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.sourceTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onTogglePin, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (entry.pinned) "Bỏ ghim" else "Ghim",
                        modifier = Modifier.size(18.dp),
                        tint = if (entry.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        }
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Sửa",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Xoá",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (entry.pinned) "Xoá mục đã ghim?" else "Xoá clipboard?") },
            text = {
                Text(
                    if (entry.pinned) {
                        "Đây là thao tác xóa riêng mục ghim này. Mục sẽ bị xóa khỏi lịch sử đồng bộ."
                    } else {
                        "Bạn có chắc muốn xóa mục clipboard này?"
                    }
                )
            },
            confirmButton = {
                Button(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("Xoá") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") }
            }
        )
    }
}

@Composable
private fun SourceBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

private data class HistoryFilterOption(
    val key: String,
    val label: String
)

private data class PendingHistoryDelete(
    val ids: List<Long>,
    val deleteAll: Boolean
)

private data class ConnectionUi(
    val title: String,
    val color: Color,
    val container: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
private fun connectionUi(state: ConnectionState): ConnectionUi {
    return when (state) {
        ConnectionState.CONNECTED -> ConnectionUi(
            title = "Đã kết nối",
            color = GreenConnected,
            container = GreenConnected.copy(alpha = 0.14f),
            icon = Icons.Default.CheckCircle
        )
        ConnectionState.CONNECTING -> ConnectionUi(
            title = "Đang kết nối",
            color = OrangeConnecting,
            container = OrangeConnecting.copy(alpha = 0.16f),
            icon = Icons.Default.Sync
        )
        ConnectionState.DISCONNECTED -> ConnectionUi(
            title = "Chưa kết nối PC",
            color = RedDisconnected,
            container = RedDisconnected.copy(alpha = 0.12f),
            icon = Icons.Default.CloudOff
        )
    }
}

private const val HISTORY_FILTER_ALL = "all"
private const val HISTORY_FILTER_PINNED = "pinned"
private const val HISTORY_FILTER_UNTAGGED = "untagged"
private const val HISTORY_FILTER_FOLDER_PREFIX = "folder:"
private const val HISTORY_FILTER_APP_PREFIX = "app:"

private fun historyGroups(entries: List<ClipboardEntry>): Map<String, List<ClipboardEntry>> {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val today = dateFormat.format(Date())
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = dateFormat.format(calendar.time)

    return entries.groupBy { entry ->
        when (val value = dateFormat.format(Date(entry.timestamp))) {
            today -> "Hôm nay"
            yesterday -> "Hôm qua"
            else -> value
        }
    }
}
