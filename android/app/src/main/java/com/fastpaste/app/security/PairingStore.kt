package com.fastpaste.app.security

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

data class PairingInvitation(
    val host: String,
    val port: Int,
    val pairId: String,
    val desktopId: String,
    val secret: ByteArray
)

data class PairedDesktop(
    val desktopId: String,
    val host: String,
    val port: Int,
    val name: String,
    val syncCursor: Long = 0L
)

class PairingStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureStore = SecureSecretStore(appContext)

    val deviceId: String by lazy {
        urlEncode(secureStore.getOrCreate(SecureSecretStore.DEVICE_IDENTITY_KEY, 16))
    }

    val deviceName: String
        get() = Build.MODEL.trim().take(80).ifBlank { "Android" }

    fun installInvitation(uriText: String): PairingInvitation {
        val uri = Uri.parse(uriText)
        require(uri.scheme == "fastpaste" && uri.host == "pair") { "QR này không phải FastPaste." }
        require(uri.getQueryParameter("v") == "2") { "Phiên bản QR chưa được hỗ trợ." }
        val invitation = PairingInvitation(
            host = requireValue(uri, "host"),
            port = requireValue(uri, "port").toInt().also { require(it in 1..65535) },
            pairId = requireValue(uri, "pairId"),
            desktopId = requireValue(uri, "desktopId"),
            secret = urlDecode(requireValue(uri, "secret")).also { require(it.size == 32) }
        )
        secureStore.put(SecureSecretStore.PENDING_PAIR_SECRET, invitation.secret)
        check(
            prefs.edit()
                .putString(KEY_PENDING_HOST, invitation.host)
                .putInt(KEY_PENDING_PORT, invitation.port)
                .putString(KEY_PENDING_PAIR_ID, invitation.pairId)
                .putString(KEY_PENDING_DESKTOP_ID, invitation.desktopId)
                .commit()
        ) { "Không lưu được lời mời ghép đôi." }
        return invitation
    }

    fun pendingForHost(host: String): PairingInvitation? {
        if (prefs.getString(KEY_PENDING_HOST, null) != host) return null
        val secret = secureStore.get(SecureSecretStore.PENDING_PAIR_SECRET) ?: return null
        return PairingInvitation(
            host = host,
            port = prefs.getInt(KEY_PENDING_PORT, 4567),
            pairId = prefs.getString(KEY_PENDING_PAIR_ID, null) ?: return null,
            desktopId = prefs.getString(KEY_PENDING_DESKTOP_ID, null) ?: return null,
            secret = secret
        )
    }

    fun completePairing(invitation: PairingInvitation, rootKey: ByteArray): PairedDesktop {
        val peer = PairedDesktop(
            desktopId = invitation.desktopId,
            host = invitation.host,
            port = invitation.port,
            name = "FastPaste PC"
        )
        secureStore.put(peerSecretName(peer.desktopId), rootKey)
        val peers = peers().filterNot { it.desktopId == peer.desktopId || it.host == peer.host } + peer
        savePeers(peers)
        clearPending()
        return peer
    }

    fun peerForHost(host: String): PairedDesktop? = peers().firstOrNull { it.host == host }

    fun peerForDesktopId(desktopId: String): PairedDesktop? =
        peers().firstOrNull { it.desktopId == desktopId }

    fun rebind(desktopId: String, host: String, port: Int): PairedDesktop? {
        var updated: PairedDesktop? = null
        val peers = peers().map { peer ->
            if (peer.desktopId == desktopId) {
                peer.copy(host = host, port = port).also { updated = it }
            } else peer
        }
        if (updated != null) savePeers(peers)
        return updated
    }

    fun rootKey(peer: PairedDesktop): ByteArray =
        secureStore.get(peerSecretName(peer.desktopId))
            ?: error("Khoá riêng của PC không còn trong Android Keystore.")

    fun peers(): List<PairedDesktop> {
        val json = runCatching { JSONArray(prefs.getString(KEY_PEERS, "[]")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val desktopId = item.optString("desktopId")
                val host = item.optString("host")
                if (desktopId.isBlank() || host.isBlank()) continue
                add(
                    PairedDesktop(
                        desktopId = desktopId,
                        host = host,
                        port = item.optInt("port", 4567),
                        name = item.optString("name", "FastPaste PC"),
                        syncCursor = item.optLong("syncCursor", 0L)
                    )
                )
            }
        }
    }

    fun forget(desktopId: String) {
        secureStore.remove(peerSecretName(desktopId))
        savePeers(peers().filterNot { it.desktopId == desktopId })
    }

    fun updateSyncCursor(desktopId: String, cursor: Long) {
        if (cursor <= 0) return
        savePeers(peers().map { peer ->
            if (peer.desktopId == desktopId && cursor > peer.syncCursor) peer.copy(syncCursor = cursor)
            else peer
        })
    }

    private fun clearPending() {
        secureStore.remove(SecureSecretStore.PENDING_PAIR_SECRET)
        prefs.edit()
            .remove(KEY_PENDING_HOST)
            .remove(KEY_PENDING_PORT)
            .remove(KEY_PENDING_PAIR_ID)
            .remove(KEY_PENDING_DESKTOP_ID)
            .apply()
    }

    private fun savePeers(peers: List<PairedDesktop>) {
        val json = JSONArray()
        peers.forEach { peer ->
            json.put(
                JSONObject()
                    .put("desktopId", peer.desktopId)
                    .put("host", peer.host)
                    .put("port", peer.port)
                    .put("name", peer.name)
                    .put("syncCursor", peer.syncCursor)
            )
        }
        check(prefs.edit().putString(KEY_PEERS, json.toString()).commit()) {
            "Không lưu được danh sách thiết bị ghép đôi."
        }
    }

    private fun peerSecretName(desktopId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(desktopId.toByteArray(Charsets.UTF_8))
        return "peer_root_v2_${urlEncode(digest.copyOf(12))}"
    }

    private fun requireValue(uri: Uri, name: String): String =
        uri.getQueryParameter(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("QR thiếu trường $name.")

    companion object {
        private const val PREFS_NAME = "fastpaste_pairing_v2"
        private const val KEY_PENDING_HOST = "pending_host"
        private const val KEY_PENDING_PORT = "pending_port"
        private const val KEY_PENDING_PAIR_ID = "pending_pair_id"
        private const val KEY_PENDING_DESKTOP_ID = "pending_desktop_id"
        private const val KEY_PEERS = "peers"

        fun randomId(size: Int = 16): String =
            urlEncode(ByteArray(size).also(SecureRandom()::nextBytes))

        fun urlEncode(value: ByteArray): String = Base64.encodeToString(
            value,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        fun urlDecode(value: String): ByteArray = Base64.decode(
            value,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
