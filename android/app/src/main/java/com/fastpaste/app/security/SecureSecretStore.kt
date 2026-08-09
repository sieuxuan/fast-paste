package com.fastpaste.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted preference vault whose wrapping key never leaves Android Keystore. */
class SecureSecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(name: String): ByteArray? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            runCatching { open(envelope, name.toByteArray(Charsets.UTF_8)) }
                .getOrElse {
                    // One-time migration for vault entries created before
                    // names were authenticated as AES-GCM associated data.
                    open(envelope).also { legacy -> put(name, legacy) }
                }
        }.getOrElse { error("Không mở được khoá bảo mật Android; dữ liệu có thể thuộc thiết bị khác.") }
    }

    @Synchronized
    fun put(name: String, secret: ByteArray) {
        val envelope = seal(secret, name.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(envelope, Base64.NO_WRAP)).commit()) {
            "Không lưu được khoá bảo mật Android."
        }
    }

    /** Encrypt arbitrary local bytes with the non-exportable Android Keystore key. */
    @Synchronized
    fun seal(plain: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        associatedData?.let(cipher::updateAAD)
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Open bytes produced by [seal]. Authentication failure is fatal. */
    @Synchronized
    fun open(envelope: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(envelope.size > NONCE_BYTES) { "Phong bì mã hoá bị thiếu dữ liệu." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateWrappingKey(),
            GCMParameterSpec(TAG_BITS, envelope, 0, NONCE_BYTES)
        )
        associatedData?.let(cipher::updateAAD)
        return cipher.doFinal(envelope, NONCE_BYTES, envelope.size - NONCE_BYTES)
    }

    @Synchronized
    fun getOrCreate(name: String, size: Int = 32): ByteArray {
        get(name)?.let { return it }
        val secret = ByteArray(size).also(SecureRandom()::nextBytes)
        put(name, secret)
        return secret
    }

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DATABASE_KEY = "sqlcipher_database_key_v1"
        const val LEGACY_E2EE_KEY = "legacy_e2ee_key_v1"
        const val DEVICE_IDENTITY_KEY = "device_identity_key_v1"
        const val PENDING_PAIR_SECRET = "pending_pair_secret_v2"
        const val HISTORY_BACKUP = "history_undo_backup_v1"
        private const val PREFS_NAME = "fastpaste_secure_vault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "fastpaste-vault-wrapping-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
