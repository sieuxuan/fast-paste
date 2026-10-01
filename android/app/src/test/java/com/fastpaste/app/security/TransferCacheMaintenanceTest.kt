package com.fastpaste.app.security

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TransferCacheMaintenanceTest {
    @Test fun cleanupKeepsRecentResumeDataAndLeavesHistoryAlone() {
        val root = Files.createTempDirectory("fastpaste-transfer-test").toFile()
        try {
            val staging = root.resolve("transfers").apply { mkdir() }
            val now = System.currentTimeMillis()
            val old = staging.resolve("old").apply { mkdir(); resolve("chunk").writeText("old"); setLastModified(now - 86_400_000) }
            val recent = staging.resolve("recent").apply { mkdir(); resolve("chunk").writeText("resume"); setLastModified(now) }
            val history = root.resolve("history.db").apply { writeText("history") }
            cleanupTransferCache(staging, now)
            assertFalse(old.exists())
            assertEquals("resume", recent.resolve("chunk").readText())
            assertEquals("history", history.readText())
        } finally { root.deleteRecursively() }
    }
}
