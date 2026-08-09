package com.fastpaste.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    onGoogleSync: () -> Unit = {},
    onScanPairingQr: () -> Unit = {},
    onSetE2eePassphrase: (String) -> Unit = {},
    onSetE2eeEnabled: (Boolean) -> Unit = {}
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
                onScanPairingQr = onScanPairingQr,
                onSetE2eePassphrase = onSetE2eePassphrase,
                onSetE2eeEnabled = onSetE2eeEnabled,
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

            if (state.transfers.isNotEmpty()) {
                item { TransferPanel(state) }
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
