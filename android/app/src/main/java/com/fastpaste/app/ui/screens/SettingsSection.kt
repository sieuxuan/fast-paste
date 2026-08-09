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
internal fun SettingsSheet(
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
    onScanPairingQr: () -> Unit,
    onSetE2eePassphrase: (String) -> Unit,
    onSetE2eeEnabled: (Boolean) -> Unit,
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

        SettingSectionTitle("00 · Ghép đôi an toàn", "Khoá riêng từng PC và session mới cho mỗi lần kết nối.")
        PairingPanel(state = state, onScanPairingQr = onScanPairingQr)

        SettingSectionTitle("01 · Mã hoá Drive", "Khoá khôi phục riêng cho bản sao trên Google Drive.")
        SecurityPanel(
            state = state,
            onSetPassphrase = onSetE2eePassphrase,
            onSetEnabled = onSetE2eeEnabled
        )

        SettingSectionTitle("02 · Kết nối PC", "Tự tìm PC trong LAN hoặc nhập IP thủ công.")
        ConnectionPanel(
            state = state,
            onConnectServer = onConnectServer,
            onDisconnect = onDisconnect,
            onManualIpChange = onManualIpChange,
            onManualPortChange = onManualPortChange,
            onConnectManual = onConnectManual,
            onRefreshDiscovery = onRefreshDiscovery
        )

        SettingSectionTitle("03 · Đồng bộ đám mây", "Lưu lịch sử riêng trong Google Drive app data.")
        CloudSyncPanel(
            state = state,
            onGoogleSync = onGoogleSync
        )

        SettingSectionTitle("04 · Cập nhật", "Kiểm tra bản Android mới trên GitHub.")
        UpdatePanel(
            state = state,
            onCheckUpdate = onCheckUpdate,
            onOpenUpdate = onOpenUpdate
        )

        SettingSectionTitle("05 · Nhật ký gần đây", "Theo dõi discover, reconnect và lần sync mới nhất.")
        ConnectionLogPanel(state = state)

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
internal fun SettingsHeader(state: UiState) {
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
internal fun SummaryPill(
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
internal fun SettingSectionTitle(title: String, subtitle: String) {
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
internal fun CloudSyncPanel(
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
internal fun UpdatePanel(
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
internal fun ConnectionLogPanel(state: UiState) {
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

