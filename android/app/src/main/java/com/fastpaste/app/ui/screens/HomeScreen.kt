package com.fastpaste.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    onClearHistory: () -> Unit,
    onRefreshDiscovery: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onGoogleSync: () -> Unit = {}
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val visibleHistory = remember(state.clipboardHistory, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            state.clipboardHistory
        } else {
            state.clipboardHistory.filter { entry ->
                entry.content.contains(query, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
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
                            Text("FastPaste", fontWeight = FontWeight.Bold)
                            Text(
                                text = "${state.clipboardHistory.size} mục clipboard",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (menuOpen) {
            ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
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
                    onGoogleSync = onGoogleSync
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HistoryHeader(
                    count = visibleHistory.size,
                    onClearHistory = onClearHistory
                )
            }

            item {
                HistorySearch(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it }
                )
            }

            item {
                StatusStrip(state = state, onOpenMenu = { menuOpen = true })
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
                            onDelete = { onDeleteItem(entry.id) }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(18.dp)) }
        }
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
    onGoogleSync: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Menu",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        ConnectionPanel(
            state = state,
            onConnectServer = onConnectServer,
            onDisconnect = onDisconnect,
            onManualIpChange = onManualIpChange,
            onManualPortChange = onManualPortChange,
            onConnectManual = onConnectManual,
            onRefreshDiscovery = onRefreshDiscovery
        )

        CloudSyncPanel(
            state = state,
            onGoogleSync = onGoogleSync
        )

        UpdatePanel(
            state = state,
            onCheckUpdate = onCheckUpdate,
            onOpenUpdate = onOpenUpdate
        )

        Spacer(Modifier.height(20.dp))
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
private fun HistoryHeader(count: Int, onClearHistory: () -> Unit) {
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
                "$count mục gần đây",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (count > 0) {
            TextButton(onClick = onClearHistory) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Xoá")
            }
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
private fun StatusStrip(
    state: UiState,
    onOpenMenu: () -> Unit
) {
    val connection = connectionUi(state.connectionState)
    val cloudText = when {
        state.cloudSyncing -> "Google đang sync"
        state.cloudSignedIn -> "Google auto"
        else -> "Google chưa login"
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
                "Copy trên điện thoại hoặc PC để bắt đầu đồng bộ.",
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
private fun HistoryItem(entry: ClipboardEntry, onDelete: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isLocal = entry.source == "LOCAL"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FastPaste", entry.content))
                Toast.makeText(context, "Đã copy", Toast.LENGTH_SHORT).show()
            },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(
                        text = if (isLocal) "Điện thoại" else "PC",
                        color = if (isLocal) LocalBadge else RemoteBadge
                    )
                    Text(
                        timeFormat.format(Date(entry.timestamp)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
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
