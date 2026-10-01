package com.fastpaste.app.security

import java.io.File

/** Discard only resumable temporary chunks, never saved history. */
internal fun cleanupTransferCache(root: File, now: Long = System.currentTimeMillis()) {
    val directories = root.listFiles().orEmpty().filter { it.isDirectory }
        .sortedByDescending { it.lastModified() }
    var retainedBytes = 0L
    directories.forEachIndexed { index, directory ->
        val bytes = directory.listFiles().orEmpty().sumOf { it.length() }
        if (now - directory.lastModified() >= 24L * 60 * 60 * 1000 || index >= 8 ||
            retainedBytes + bytes > 256L * 1024 * 1024) {
            directory.deleteRecursively()
        } else retainedBytes += bytes
    }
}
