package com.fastpaste.app.sync

import android.content.Context
import android.util.Base64
import com.fastpaste.app.security.SecureSecretStore
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionStore(context: Context) {
    private val secureStore = SecureSecretStore(context)
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    val keyId: String
        get() = loadKey()?.let(::keyId).orEmpty()

    fun setPassphrase(passphrase: String): String {
        require(passphrase.length >= 10) { "Mật khẩu mã hoá cần ít nhất 10 ký tự." }
        val key = deriveKey(passphrase)
        val id = keyId(key)
        val currentKeyId = loadKey()?.let(::keyId).orEmpty()
        require(!isEnabled || currentKeyId.isEmpty() || currentKeyId == id) {
            "Để đổi khoá an toàn: tắt E2EE, sync Drive về plaintext, rồi nhập khoá mới."
        }
        secureStore.put(SecureSecretStore.LEGACY_E2EE_KEY, key)
        prefs.edit().remove(KEY_MATERIAL).putBoolean(KEY_ENABLED, true).apply()
        return id
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) require(loadKey() != null) { "Hãy nhập mật khẩu E2EE trên thiết bị này trước." }
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun protect(plain: String, type: String = TYPE_ENCRYPTED): String {
        if (!isEnabled) return plain
        val key = loadKey() ?: error("Thiết bị chưa có khoá E2EE.")
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("app", "fastpaste")
            .put("type", type)
            .put("version", 1)
            .put("keyId", keyId(key))
            .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString()
    }

    /** Returns null when [input] is not an encrypted FastPaste envelope. */
    fun decryptIfEncrypted(input: String): String? {
        val json = runCatching { JSONObject(input) }.getOrNull() ?: return null
        val type = json.optString("type")
        if (json.optString("app") != "fastpaste" ||
            json.optInt("version") != 1 ||
            type !in setOf(TYPE_ENCRYPTED, TYPE_ENCRYPTED_DRIVE, TYPE_ENCRYPTED_DRIVE_BLOB)
        ) return null

        val key = loadKey() ?: error("Thiết bị chưa có khoá E2EE.")
        require(json.optString("keyId") == keyId(key)) {
            "Khoá E2EE không khớp giữa các thiết bị."
        }
        val nonce = Base64.decode(json.getString("nonce"), Base64.DEFAULT)
        require(nonce.size == NONCE_BYTES) { "Nonce E2EE không hợp lệ." }
        val ciphertext = Base64.decode(json.getString("ciphertext"), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun loadKey(): ByteArray? {
        secureStore.get(SecureSecretStore.LEGACY_E2EE_KEY)
            ?.takeIf { it.size == KEY_BYTES }
            ?.let { return it }

        // One-time migration from the plaintext SharedPreferences used by
        // FastPaste 2.2.6. Remove it only after Keystore wrapping succeeds.
        val encoded = prefs.getString(KEY_MATERIAL, null) ?: return null
        val key = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrNull()
            ?.takeIf { it.size == KEY_BYTES }
            ?: return null
        secureStore.put(SecureSecretStore.LEGACY_E2EE_KEY, key)
        prefs.edit().remove(KEY_MATERIAL).apply()
        return key
    }

    private fun deriveKey(passphrase: String): ByteArray {
        // Work from explicit UTF-8 bytes so Unicode passphrases derive the
        // exact same key as Rust on Windows, independent of JCA provider.
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val block = SALT + byteArrayOf(0, 0, 0, 1)
        var previous = mac.doFinal(block)
        val derived = previous.copyOf()
        repeat(PBKDF2_ROUNDS - 1) {
            previous = mac.doFinal(previous)
            for (index in derived.indices) {
                derived[index] = (derived[index].toInt() xor previous[index].toInt()).toByte()
            }
        }
        return derived.copyOf(KEY_BYTES)
    }

    private fun keyId(key: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(key)
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val TYPE_ENCRYPTED = "encrypted"
        const val TYPE_ENCRYPTED_DRIVE = "encrypted_drive"
        const val TYPE_ENCRYPTED_DRIVE_BLOB = "encrypted_drive_blob"
        private const val PREFS_NAME = "fastpaste_e2ee"
        private const val KEY_MATERIAL = "derived_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val PBKDF2_ROUNDS = 210_000
        private val SALT = "FastPaste E2EE v1".toByteArray(Charsets.UTF_8)
    }
}
