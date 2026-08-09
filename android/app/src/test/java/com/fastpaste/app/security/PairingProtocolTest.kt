package com.fastpaste.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun `pair root and session HKDF match desktop vector`() {
        val secret = ByteArray(32) { it.toByte() }
        val root = SecureChannel.hkdf(
            salt = "pair-test".toByteArray(),
            ikm = secret,
            info = "fastpaste-pair-v2|android-test|desktop-test|nonce-test".toByteArray()
        )
        assertEquals(
            "00cb8a0a16c6f19767b5d891603d3fb50c3e50b132b042bef695f254237a982d",
            root.hex()
        )

        val shared = ByteArray(32) { (it + 32).toByte() }
        val session = SecureChannel.hkdf(root, shared, "session-vector".toByteArray())
        assertEquals(
            "1e37c14317667d6c6c2c0f460d09470e7f986c2ad7c8e0ec8882e5cc1d725d46",
            session.hex()
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
