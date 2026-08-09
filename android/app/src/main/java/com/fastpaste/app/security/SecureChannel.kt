package com.fastpaste.app.security

import android.content.Context
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SecureChannelEvent(
    val consumed: Boolean,
    val connected: Boolean = false,
    val message: String? = null,
    val status: String? = null
)

/** Pairing plus authenticated ephemeral P-256 session for one WebSocket connection. */
class SecureChannel(context: Context, private val host: String) {
    private val store = PairingStore(context)
    private var invitation: PairingInvitation? = store.pendingForHost(host)
    private var peer: PairedDesktop? = store.peerForHost(host)
    private var pairRequestNonce: String? = null
    private var sessionNonce: String? = null
    private var sessionKeyPair: KeyPair? = null
    private var sessionCipher: SessionCipher? = null
    var remoteSyncCursor: Long = 0L
        private set

    val isSecure: Boolean get() = sessionCipher != null
    val requiresPairing: Boolean get() = invitation == null && peer == null

    /** Returns true when a secure handshake was started, false for legacy mode. */
    fun start(sendRaw: (String) -> Boolean): Boolean {
        sessionCipher?.close()
        sessionCipher = null
        sessionKeyPair = null
        sessionNonce = null
        invitation?.let {
            sendPairRequest(it, sendRaw)
            return true
        }
        peer?.let {
            sendSessionHello(it, sendRaw)
            return true
        }
        // Secure by default: an unpaired socket stays in the handshake state
        // and never exposes clipboard data. Scanning QR reconnects with an
        // authenticated invitation.
        return true
    }

    fun handleIncoming(text: String, sendRaw: (String) -> Boolean): SecureChannelEvent {
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json?.optString("app") == "fastpaste") {
            when (json.optString("type")) {
                "pair_accept" -> return handlePairAccept(json, sendRaw)
                "session_accept" -> return handleSessionAccept(json)
                "secure_v2" -> {
                    val plain = sessionCipher?.decrypt(json)
                        ?: return SecureChannelEvent(true, status = "Đã chặn dữ liệu trước handshake")
                    return SecureChannelEvent(true, message = plain)
                }
            }
        }
        return SecureChannelEvent(true, status = "Đã chặn dữ liệu plaintext; cần ghép đôi QR")
    }

    fun protect(plain: String): String = sessionCipher?.encrypt(plain) ?: plain

    fun protectBinary(plain: ByteArray): ByteArray? = sessionCipher?.encryptBinary(plain)

    fun unprotectBinary(wire: ByteArray): ByteArray? = sessionCipher?.decryptBinary(wire)

    private fun sendPairRequest(invitation: PairingInvitation, sendRaw: (String) -> Boolean) {
        val nonce = PairingStore.randomId()
        pairRequestNonce = nonce
        val transcript = "pair_request|${invitation.pairId}|${store.deviceId}|${store.deviceName}|$nonce"
        val request = JSONObject()
            .put("app", "fastpaste")
            .put("type", "pair_request")
            .put("version", 2)
            .put("pairId", invitation.pairId)
            .put("deviceId", store.deviceId)
            .put("deviceName", store.deviceName)
            .put("nonce", nonce)
            .put("proof", mac(invitation.secret, transcript))
        check(sendRaw(request.toString())) { "Không gửi được yêu cầu ghép đôi." }
    }

    private fun handlePairAccept(json: JSONObject, sendRaw: (String) -> Boolean): SecureChannelEvent {
        val pending = invitation ?: return SecureChannelEvent(true, status = "Không có QR ghép đôi đang chờ")
        val requestNonce = pairRequestNonce ?: return SecureChannelEvent(true)
        require(json.optString("pairId") == pending.pairId)
        require(json.optString("desktopId") == pending.desktopId)
        require(json.optString("deviceId") == store.deviceId)
        val responseNonce = json.getString("nonce")
        val root = hkdf(
            salt = pending.pairId.toByteArray(Charsets.UTF_8),
            ikm = pending.secret,
            info = "fastpaste-pair-v2|${store.deviceId}|${pending.desktopId}|$requestNonce".toByteArray(Charsets.UTF_8)
        )
        val transcript = "pair_accept|${pending.pairId}|${pending.desktopId}|${store.deviceId}|$requestNonce|$responseNonce"
        verifyMac(root, transcript, json.getString("proof"))
        peer = store.completePairing(pending, root)
        pending.secret.fill(0)
        root.fill(0)
        invitation = null
        sendSessionHello(peer!!, sendRaw)
        return SecureChannelEvent(true, status = "Đã ghép đôi; đang tạo session bảo mật")
    }

    private fun sendSessionHello(peer: PairedDesktop, sendRaw: (String) -> Boolean) {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        sessionKeyPair = keyPair
        val publicKey = PairingStore.urlEncode(toSec1(keyPair.public as ECPublicKey))
        val nonce = PairingStore.randomId()
        sessionNonce = nonce
        val transcript = "session_hello|${store.deviceId}|${peer.desktopId}|$publicKey|$nonce|${peer.syncCursor}"
        val root = store.rootKey(peer)
        val request = JSONObject()
            .put("app", "fastpaste")
            .put("type", "session_hello")
            .put("version", 2)
            .put("deviceId", store.deviceId)
            .put("ephemeralPublic", publicKey)
            .put("nonce", nonce)
            .put("syncCursor", peer.syncCursor)
            .put("proof", mac(root, transcript))
        root.fill(0)
        check(sendRaw(request.toString())) { "Không gửi được session hello." }
    }

    private fun handleSessionAccept(json: JSONObject): SecureChannelEvent {
        val paired = peer ?: return SecureChannelEvent(true, status = "PC chưa được ghép đôi")
        require(json.optString("desktopId") == paired.desktopId)
        require(json.optString("deviceId") == store.deviceId)
        val keyPair = sessionKeyPair ?: error("Thiếu ephemeral key Android.")
        val requestNonce = sessionNonce ?: error("Thiếu session nonce Android.")
        val ownPublic = PairingStore.urlEncode(toSec1(keyPair.public as ECPublicKey))
        val remotePublic = json.getString("ephemeralPublic")
        val responseNonce = json.getString("nonce")
        remoteSyncCursor = json.optLong("syncCursor", 0L)
        val transcript = "session_v2|${store.deviceId}|${paired.desktopId}|$ownPublic|$remotePublic|$requestNonce|$responseNonce|$remoteSyncCursor"
        val root = store.rootKey(paired)
        verifyMac(root, transcript, json.getString("proof"))
        val remoteKey = fromSec1(PairingStore.urlDecode(remotePublic), keyPair.public as ECPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(remoteKey, true)
        val shared = agreement.generateSecret()
        val key = hkdf(root, shared, transcript.toByteArray(Charsets.UTF_8))
        sessionCipher = SessionCipher(key, store.deviceId)
        root.fill(0)
        shared.fill(0)
        key.fill(0)
        sessionKeyPair = null
        return SecureChannelEvent(
            consumed = true,
            connected = true,
            status = "Kết nối E2EE · forward secrecy"
        )
    }

    fun close() {
        sessionCipher?.close()
        sessionCipher = null
        sessionKeyPair = null
        sessionNonce = null
        pairRequestNonce = null
        invitation?.secret?.fill(0)
        invitation = null
    }

    private class SessionCipher(key: ByteArray, private val deviceId: String) {
        private val key = key.copyOf()
        private var sendSequence = 0L
        private var receiveSequence = 0L

        fun encrypt(plain: String): String {
            sendSequence = Math.addExact(sendSequence, 1L)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce("ANV2", sendSequence))
            )
            cipher.updateAAD("fastpaste-secure-v2|$deviceId|$sendSequence".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return JSONObject()
                .put("app", "fastpaste")
                .put("type", "secure_v2")
                .put("version", 2)
                .put("seq", sendSequence)
                .put("ciphertext", PairingStore.urlEncode(encrypted))
                .toString()
        }

        fun decrypt(json: JSONObject): String {
            val sequence = json.getLong("seq")
            require(sequence > receiveSequence) { "Đã chặn message bị phát lại." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce("PCV2", sequence))
            )
            cipher.updateAAD("fastpaste-secure-v2|$deviceId|$sequence".toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(PairingStore.urlDecode(json.getString("ciphertext")))
            receiveSequence = sequence
            return plain.toString(Charsets.UTF_8)
        }

        fun encryptBinary(plain: ByteArray): ByteArray {
            sendSequence = Math.addExact(sendSequence, 1L)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce("ANV2", sendSequence))
            )
            cipher.updateAAD("fastpaste-secure-v2|$deviceId|$sendSequence".toByteArray(Charsets.UTF_8))
            return "FPS3".toByteArray(Charsets.US_ASCII) + longBytes(sendSequence) + cipher.doFinal(plain)
        }

        fun decryptBinary(wire: ByteArray): ByteArray? {
            if (wire.size < 12 || !wire.copyOfRange(0, 4).contentEquals("FPS3".toByteArray(Charsets.US_ASCII))) {
                return null
            }
            val sequence = bytesLong(wire, 4)
            require(sequence > receiveSequence) { "Đã chặn binary message bị phát lại." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce("PCV2", sequence))
            )
            cipher.updateAAD("fastpaste-secure-v2|$deviceId|$sequence".toByteArray(Charsets.UTF_8))
            val plain = cipher.doFinal(wire.copyOfRange(12, wire.size))
            receiveSequence = sequence
            return plain
        }

        fun close() {
            key.fill(0)
            sendSequence = 0L
            receiveSequence = 0L
        }

        private fun nonce(prefix: String, sequence: Long): ByteArray =
            prefix.toByteArray(Charsets.US_ASCII) + ByteArray(8).also { bytes ->
                for (index in 0 until 8) {
                    bytes[7 - index] = (sequence ushr (index * 8)).toByte()
                }
            }

        private fun longBytes(value: Long): ByteArray = ByteArray(8).also { bytes ->
            for (index in 0 until 8) bytes[7 - index] = (value ushr (index * 8)).toByte()
        }

        private fun bytesLong(bytes: ByteArray, start: Int): Long {
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (bytes[start + index].toLong() and 0xff)
            return value
        }
    }

    companion object {
        private fun mac(key: ByteArray, text: String): String {
            val hmac = Mac.getInstance("HmacSHA256")
            hmac.init(SecretKeySpec(key, "HmacSHA256"))
            return PairingStore.urlEncode(hmac.doFinal(text.toByteArray(Charsets.UTF_8)))
        }

        private fun verifyMac(key: ByteArray, text: String, proof: String) {
            require(MessageDigest.isEqual(PairingStore.urlDecode(proof), PairingStore.urlDecode(mac(key, text)))) {
                "Xác thực thiết bị thất bại."
            }
        }

        internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(salt, "HmacSHA256"))
                doFinal(ikm)
            }
            var previous = ByteArray(0)
            val output = ArrayList<Byte>(32)
            var counter = 1
            while (output.size < 32) {
                previous = Mac.getInstance("HmacSHA256").run {
                    init(SecretKeySpec(extract, "HmacSHA256"))
                    doFinal(previous + info + counter.toByte())
                }
                previous.forEach { output.add(it) }
                counter++
            }
            return output.take(32).toByteArray()
        }

        private fun toSec1(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + unsigned32(key.w.affineX) + unsigned32(key.w.affineY)

        private fun fromSec1(encoded: ByteArray, template: ECPublicKey): ECPublicKey {
            require(encoded.size == 65 && encoded[0] == 4.toByte()) { "P-256 public key không hợp lệ." }
            val point = ECPoint(
                BigInteger(1, encoded.copyOfRange(1, 33)),
                BigInteger(1, encoded.copyOfRange(33, 65))
            )
            return KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(point, template.params)) as ECPublicKey
        }

        private fun unsigned32(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            require(bytes.size <= 32)
            return ByteArray(32 - bytes.size) + bytes
        }
    }
}
