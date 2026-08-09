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
internal fun EmptyHistory() {
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
internal fun EmptySearch() {
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
internal fun EditHistoryDialog(
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
                if (entry.payloadType != "text") {
                    SourceBadge(
                        text = when (entry.payloadType) {
                            "image" -> "Ảnh · sửa chú thích, giữ nguyên dữ liệu"
                            "html" -> "Rich text"
                            else -> entry.payloadType
                        },
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(if (entry.payloadType == "image") "Chú thích" else "Nội dung") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    minLines = 5
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { content = content.trim() }) { Text("Gọn") }
                    TextButton(onClick = { content = content.lowercase() }) { Text("a") }
                    TextButton(onClick = { content = content.uppercase() }) { Text("A") }
                    TextButton(onClick = {
                        content = content.lines().joinToString("\n") { line ->
                            if (line.isBlank()) "" else "- ${line.trim().removePrefix("- ")}"
                        }
                    }) { Text("• Dòng") }
                }
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Nhãn") },
                    placeholder = { Text("Công việc, Code, Cá nhân…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "${content.length} ký tự · ${content.lines().size} dòng",
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
internal fun HistoryItem(
    entry: ClipboardEntry,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val isLocal = entry.source == "LOCAL"
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    var previewOpen by remember(entry.id) { mutableStateOf(false) }
    val thumbnail = remember(entry.thumbnail) { decodeDataUriImage(entry.thumbnail) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    thumbnail?.let { preview ->
                        Image(
                            bitmap = preview,
                            contentDescription = "Xem trước ảnh",
                            modifier = Modifier
                                .size(width = 86.dp, height = 64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { previewOpen = true },
                            contentScale = ContentScale.Crop
                        )
                    }
                    Text(
                        entry.content,
                        modifier = Modifier.weight(1f),
                        maxLines = if (thumbnail == null) 4 else 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
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
                        SourceAppBadge(entry.sourceApp, entry.sourceIcon)
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

    if (previewOpen && entry.payloadType == "image") {
        val fullPreview = remember(entry.payloadData) {
            decodeBase64Image(entry.payloadData) ?: thumbnail
        }
        AlertDialog(
            onDismissRequest = { previewOpen = false },
            title = { Text("Xem trước ảnh") },
            text = {
                fullPreview?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = "Ảnh clipboard",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCopy()
                    previewOpen = false
                }) { Text("Sao chép") }
            },
            dismissButton = {
                TextButton(onClick = { previewOpen = false }) { Text("Đóng") }
            }
        )
    }
}

@Composable
internal fun SourceBadge(text: String, color: Color) {
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

@Composable
internal fun SourceAppBadge(appName: String, iconData: String) {
    val appIcon = remember(iconData) { decodeDataUriImage(iconData) }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Text(
                    appName.removeSuffix(".exe").take(1).uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Text(
                appName.removeSuffix(".exe"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

