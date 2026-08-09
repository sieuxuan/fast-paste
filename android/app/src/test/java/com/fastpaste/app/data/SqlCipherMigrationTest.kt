package com.fastpaste.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SqlCipherMigrationTest {
    @Test
    fun detectsPlaintextHeaderButNotEncryptedOrTruncatedFiles() {
        val directory = createTempDirectory("fastpaste-db-test").toFile()
        try {
            val plaintext = File(directory, "plain.db").apply {
                writeBytes("SQLite format 3\u0000rest".toByteArray(Charsets.US_ASCII))
            }
            val encrypted = File(directory, "encrypted.db").apply {
                writeBytes(ByteArray(64) { index -> (index * 17 + 3).toByte() })
            }
            val truncated = File(directory, "short.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertTrue(SqlCipherMigration.isPlaintext(plaintext))
            assertFalse(SqlCipherMigration.isPlaintext(encrypted))
            assertFalse(SqlCipherMigration.isPlaintext(truncated))
            assertFalse(SqlCipherMigration.isPlaintext(File(directory, "missing.db")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
