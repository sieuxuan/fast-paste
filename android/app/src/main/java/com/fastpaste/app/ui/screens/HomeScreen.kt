package com.fastpaste.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fastpaste.app.UiState
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.discovery.DiscoveredServer
import com.fastpaste.app.ui.theme.*
import com.fastpaste.app.websocket.ConnectionState
import java.text.SimpleDateFormat
import java.util.*

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
    onRefreshDiscovery: () -> Unit = {}
) {
    var showConnectionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Fast Paste", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showConnectionDialog = true }) {
                        val iconColor = if (state.connectionState == ConnectionState.CONNECTED) GreenConnected else MaterialTheme.colorScheme.onSurface
                        Icon(Icons.Default.Wifi, contentDescription = "Connect", tint = iconColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lịch Sử Copy", style = MaterialTheme.typography.titleLarge)
                    if (state.clipboardHistory.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) { Text("Xoá tất cả") }
                    }
                }
            }

            if (state.clipboardHistory.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "Chưa có dữ liệu copy",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            val grouped = state.clipboardHistory.groupBy {
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it.timestamp))
            }

            val todayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val yesterdayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(calendar.time)

            grouped.forEach { (dateStr, items) ->
                item {
                    val header = when (dateStr) {
                        todayStr -> "Hôm nay"
                        yesterdayStr -> "Hôm qua"
                        else -> dateStr
                    }
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }

                items(items, key = { it.id }) { entry ->
                    HistoryItem(
                        entry = entry,
                        onDelete = { onDeleteItem(entry.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showConnectionDialog) {
        AlertDialog(
            onDismissRequest = { showConnectionDialog = false },
            title = { Text("Quản Lý Kết Nối") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionCard(
                        state = state.connectionState,
                        serverName = state.connectedServer,
                        onDisconnect = onDisconnect
                    )

                    if (state.connectionState == ConnectionState.DISCONNECTED) {
                        ManualConnectionCard(
                            ip = state.manualIp,
                            port = state.manualPort,
                            onIpChange = onManualIpChange,
                            onPortChange = onManualPortChange,
                            onConnect = {
                                onConnectManual()
                                showConnectionDialog = false
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (state.discoveredServers.isNotEmpty()) "PC tự động tìm thấy:" else "Đang tìm PC...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            TextButton(onClick = onRefreshDiscovery) { Text("Quét lại") }
                        }

                        if (state.discoveredServers.isNotEmpty()) {
                            state.discoveredServers.forEach { server ->
                                ServerCard(server = server, onClick = {
                                    onConnectServer(server.host, server.port)
                                    showConnectionDialog = false
                                })
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectionDialog = false }) { Text("Đóng") }
            }
        )
    }
}

@Composable
private fun ConnectionCard(
    state: ConnectionState,
    serverName: String?,
    onDisconnect: () -> Unit
) {
    val (statusColor, statusText, statusIcon) = when (state) {
        ConnectionState.CONNECTED -> Triple(GreenConnected, "Đã kết nối", Icons.Default.CheckCircle)
        ConnectionState.CONNECTING -> Triple(OrangeConnecting, "Đang kết nối...", Icons.Default.Sync)
        ConnectionState.DISCONNECTED -> Triple(RedDisconnected, "Đã ngắt kết nối", Icons.Default.CloudOff)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(statusText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (serverName != null) {
                        Text(serverName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                }
            }
            if (state != ConnectionState.DISCONNECTED) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("Ngắt kết nối")
                }
            }
        }
    }
}

@Composable
private fun ServerCard(server: DiscoveredServer, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ManualConnectionCard(
    ip: String,
    port: String,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Nhập IP thủ công", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = onIpChange,
                    label = { Text("IP") },
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
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = ip.isNotBlank()) {
                Text("Kết nối")
            }
        }
    }
}

@Composable
private fun HistoryItem(entry: ClipboardEntry, onDelete: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Fast Paste", entry.content))
                Toast.makeText(context, "Đã copy!", Toast.LENGTH_SHORT).show()
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.content, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val badgeColor = if (entry.source == "LOCAL") LocalBadge else RemoteBadge
                    val badgeText = if (entry.source == "LOCAL") "📱 Điện thoại" else "💻 PC"
                    Text(badgeText, fontSize = 11.sp, color = badgeColor, fontWeight = FontWeight.Medium)
                    Text(timeFormat.format(Date(entry.timestamp)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Xoá", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    }
}
