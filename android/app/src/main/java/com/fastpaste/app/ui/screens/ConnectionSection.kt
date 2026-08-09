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
internal fun ConnectionPanel(
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
                if (state.connectionState == ConnectionState.CONNECTED_SECURE) {
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
internal fun DiscoveredServers(
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
internal fun ServerRow(server: DiscoveredServer, onClick: () -> Unit) {
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
internal fun ManualConnect(
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

internal data class ConnectionUi(
    val title: String,
    val color: Color,
    val container: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
internal fun connectionUi(state: ConnectionState): ConnectionUi {
    return when (state) {
        ConnectionState.CONNECTED_SECURE -> ConnectionUi(
            title = "Đã kết nối",
            color = GreenConnected,
            container = GreenConnected.copy(alpha = 0.14f),
            icon = Icons.Default.CheckCircle
        )
        ConnectionState.CONNECTED_UNPAIRED -> ConnectionUi(
            title = "Chưa ghép đôi",
            color = OrangeConnecting,
            container = OrangeConnecting.copy(alpha = 0.16f),
            icon = Icons.Default.Warning
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

