package com.fastpaste.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import com.fastpaste.app.ui.screens.HomeScreen
import com.fastpaste.app.ui.theme.FastPasteTheme
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var lastSilentCloudSyncAt = 0L

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
        requestNotificationPermission()

        // Handle text shared from other apps
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val serviceIntent = Intent(this, com.fastpaste.app.service.ClipboardService::class.java).apply {
                    action = com.fastpaste.app.service.ClipboardService.ACTION_SEND_TEXT
                    putExtra(com.fastpaste.app.service.ClipboardService.EXTRA_TEXT, sharedText)
                }
                startService(serviceIntent)
                Toast.makeText(this, "Đã gửi tới PC", Toast.LENGTH_SHORT).show()
                finish() // Close UI immediately after sharing
                return
            }
        }

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
                        onTogglePin = viewModel::toggleHistoryPin,
                        onClearHistory = viewModel::clearHistory,
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

    // Track last synced text to avoid duplicate sends on every focus
    private var lastFocusSyncedText: String? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Only sync clipboard when connected and text is genuinely new
            val currentState = viewModel.uiState.value
            if (currentState.connectionState == com.fastpaste.app.websocket.ConnectionState.CONNECTED) {
                try {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString()
                        if (!text.isNullOrBlank() && text != lastFocusSyncedText) {
                            lastFocusSyncedText = text
                            val intent = Intent(this, com.fastpaste.app.service.ClipboardService::class.java).apply {
                                action = com.fastpaste.app.service.ClipboardService.ACTION_SEND_TEXT
                                putExtra(com.fastpaste.app.service.ClipboardService.EXTRA_TEXT, text)
                            }
                            startService(intent)
                        }
                    }
                } catch (_: Exception) { /* Clipboard access may fail on some devices */ }
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
