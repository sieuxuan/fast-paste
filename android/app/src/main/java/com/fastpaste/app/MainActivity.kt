package com.fastpaste.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fastpaste.app.data.ClipboardPayload
import com.fastpaste.app.service.ClipboardService
import com.fastpaste.app.sync.AndroidClipboardCodec
import com.fastpaste.app.ui.screens.HomeScreen
import com.fastpaste.app.ui.theme.FastPasteTheme
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var lastSilentCloudSyncAt = 0L
    private var handlingShare = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, service still works */ }

    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.setCloudMessage("Bạn đã hủy cấp quyền Google Drive.")
            return@registerForActivityResult
        }

        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            viewModel.syncGoogleDrive(authorizationResult.accessToken, rememberLogin = true)
        } catch (error: ApiException) {
            viewModel.setCloudMessage("Google Drive chưa được cấp quyền: ${error.statusCode}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (handleSharedIntent(intent)) return
        requestNotificationPermission()

        setContent {
            FastPasteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by viewModel.uiState.collectAsState()

                    HomeScreen(
                        state = state,
                        onConnectServer = viewModel::connectToServer,
                        onDisconnect = viewModel::disconnectFromServer,
                        onManualIpChange = viewModel::updateManualIp,
                        onManualPortChange = viewModel::updateManualPort,
                        onConnectManual = viewModel::connectManual,
                        onDeleteItem = viewModel::deleteHistoryItem,
                        onDeleteItems = viewModel::deleteHistoryItems,
                        onTogglePin = viewModel::toggleHistoryPin,
                        onClearHistory = viewModel::clearHistory,
                        onUndoHistoryDelete = viewModel::undoHistoryDelete,
                        onCopyItem = viewModel::copyHistoryItem,
                        onEditItem = viewModel::updateHistoryItem,
                        onRefreshDiscovery = viewModel::restartDiscovery,
                        onCheckUpdate = viewModel::checkForUpdates,
                        onOpenUpdate = viewModel::openUpdatePage,
                        onGoogleSync = ::requestGoogleDriveSync
                    )
                }
            }
        }

        if (viewModel.isCloudSyncEnabled()) {
            requestGoogleDriveSync(interactive = false)
        }
    }

    private fun handleSharedIntent(sharedIntent: Intent?): Boolean {
        val share = sharedIntent ?: return false
        if (share.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            return false
        }
        handlingShare = true

        lifecycleScope.launch(Dispatchers.IO) {
            val sharedText = share.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val sharedHtml = share.getStringExtra(Intent.EXTRA_HTML_TEXT).orEmpty()
            val payload = if (sharedText.isNotBlank() && share.clipData?.hasUris() != true) {
                if (share.type == "text/html" || sharedHtml.isNotBlank()) {
                    ClipboardPayload(
                        kind = ClipboardPayload.KIND_HTML,
                        text = sharedText,
                        html = sharedHtml.ifBlank { sharedText },
                        mimeType = "text/html"
                    )
                } else {
                    ClipboardPayload.text(sharedText)
                }
            } else {
                AndroidClipboardCodec.read(
                    this@MainActivity,
                    share.clipData ?: buildStreamClip(share)
                )
            }

            val clip = payload?.let {
                runCatching { AndroidClipboardCodec.write(this@MainActivity, it) }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                if (clip == null) {
                    Toast.makeText(this@MainActivity, "Không đọc được nội dung chia sẻ", Toast.LENGTH_SHORT).show()
                } else {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(clip)
                    startService(Intent(this@MainActivity, ClipboardService::class.java).apply {
                        action = ClipboardService.ACTION_SYNC_CURRENT_CLIP
                    })
                    Toast.makeText(this@MainActivity, "Đã gửi tới PC", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }
        return true
    }

    private fun ClipData.hasUris(): Boolean {
        for (index in 0 until itemCount) {
            if (getItemAt(index).uri != null) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun buildStreamClip(sharedIntent: Intent): ClipData? {
        val uris = if (sharedIntent.action == Intent.ACTION_SEND_MULTIPLE) {
            sharedIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        if (uris.isEmpty()) return null
        return ClipData.newUri(contentResolver, "Fast Paste", uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handlingShare) {
            // Android may suppress clipboard callbacks while this app is in the
            // background, so ask the foreground service to inspect every format
            // once when the UI regains focus. Payload fingerprints prevent loops.
            val currentState = viewModel.uiState.value
            if (currentState.connectionState == com.fastpaste.app.websocket.ConnectionState.CONNECTED) {
                val intent = Intent(this, com.fastpaste.app.service.ClipboardService::class.java).apply {
                    action = com.fastpaste.app.service.ClipboardService.ACTION_SYNC_CURRENT_CLIP
                }
                startService(intent)
            }

            if (viewModel.isCloudSyncEnabled()) {
                requestGoogleDriveSync(interactive = false)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestGoogleDriveSync(interactive: Boolean = true) {
        if (!interactive) {
            if (!viewModel.isCloudSyncEnabled()) return

            val now = System.currentTimeMillis()
            if (now - lastSilentCloudSyncAt < SILENT_CLOUD_SYNC_INTERVAL_MS) return
            lastSilentCloudSyncAt = now
        } else {
            viewModel.setCloudMessage("Đang mở đăng nhập Google...")
        }

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_DRIVE_APPDATA_SCOPE)))
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { authorizationResult ->
                if (authorizationResult.hasResolution()) {
                    val pendingIntent = authorizationResult.pendingIntent
                    if (!interactive) {
                        viewModel.setCloudMessage("Google Drive cần đăng nhập lại.")
                    } else if (pendingIntent == null) {
                        viewModel.setCloudMessage("Google Drive cần xác nhận lại.")
                    } else {
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    viewModel.syncGoogleDrive(authorizationResult.accessToken, rememberLogin = true)
                }
            }
            .addOnFailureListener { error ->
                if (interactive) {
                    viewModel.setCloudMessage("Không mở được đăng nhập Google: ${error.message ?: "lỗi không rõ"}")
                }
            }
    }

    companion object {
        private const val GOOGLE_DRIVE_APPDATA_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata"
        private const val SILENT_CLOUD_SYNC_INTERVAL_MS = 60_000L
    }
}
