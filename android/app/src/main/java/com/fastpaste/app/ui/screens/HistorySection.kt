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
internal fun HistoryHeader(
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
internal fun TransferPanel(state: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            state.transfers.forEach { transfer ->
                val fraction = if (transfer.totalBytes > 0) {
                    (transfer.sentBytes.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transfer.label.ifBlank { "Ảnh / tệp" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${if (transfer.direction == "upload") "Gửi" else "Tải"} · ${transfer.status} · ${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                        )
                    }
                }
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun PairingPanel(
    state: UiState,
    onScanPairingQr: () -> Unit
) {
    val paired = state.pairedDeviceCount > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (paired) GreenConnected.copy(alpha = 0.09f)
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (paired) Icons.Default.CheckCircle else Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = if (paired) GreenConnected else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (paired) "Đã ghép đôi ${state.pairedDeviceCount} PC" else "Chưa ghép đôi",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        state.pairingMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                    )
                }
            }
            Button(onClick = onScanPairingQr, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (paired) "Ghép đôi PC khác" else "Quét QR trên PC")
            }
            Text(
                "QR dùng một lần. Mỗi PC có root key riêng; mỗi reconnect tạo P-256 ECDH session mới để có forward secrecy.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
            )
        }
    }
}

@Composable
internal fun SecurityPanel(
    state: UiState,
    onSetPassphrase: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit
) {
    var passphrase by rememberSaveable { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.e2eeEnabled) {
                GreenConnected.copy(alpha = 0.09f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.e2eeEnabled) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (state.e2eeEnabled) GreenConnected else OrangeConnecting
                )
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.e2eeEnabled) "Drive E2EE đang bật" else "Drive E2EE chưa bật",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        state.e2eeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
                    )
                    if (state.e2eeKeyId.isNotBlank()) {
                        Text(
                            "Mã khoá: ${state.e2eeKeyId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Khoá khôi phục Drive") },
                placeholder = { Text("Dùng khi cài lại hoặc thêm thiết bị") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSetPassphrase(passphrase)
                        passphrase = ""
                    },
                    enabled = passphrase.length >= 10,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.e2eeEnabled) "Xác nhận khoá" else "Mã hoá Drive")
                }
                if (state.e2eeKeyId.isNotBlank()) {
                    OutlinedButton(
                        onClick = { onSetEnabled(!state.e2eeEnabled) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.e2eeEnabled) "Tắt" else "Bật lại")
                    }
                }
            }
            Text(
                "Khoá này chỉ bảo vệ bản sao Drive. Kết nối PC dùng khoá thiết bị từ QR, không dùng mật khẩu chung.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
            )
        }
    }
}

@Composable
internal fun UndoHistoryBanner(count: Int, onUndo: () -> Unit) {
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
internal fun HistorySearch(
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
internal fun HistoryFilters(
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
internal fun FilterChip(
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
internal fun StatusStrip(
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
internal fun CompactChip(
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

