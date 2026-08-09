package com.fastpaste.app.sync

import android.content.Context
import com.fastpaste.app.data.ClipboardEntry
import com.fastpaste.app.security.SecureSecretStore
import org.json.JSONArray
import org.json.JSONObject

class HistoryBackupStore(context: Context) {
    private val secureStore = SecureSecretStore(context)
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(entries: List<ClipboardEntry>) {
        val payload = JSONArray()
        entries.forEach { entry ->
            payload.put(
                JSONObject()
                    .put("content", entry.content)
                    .put("source", entry.source)
                    .put("sourceApp", entry.sourceApp)
                    .put("sourceTitle", entry.sourceTitle)
                    .put("sourceIcon", entry.sourceIcon)
                    .put("timestamp", entry.timestamp)
                    .put("pinned", entry.pinned)
                    .put("folder", entry.folder)
                    .put("payloadType", entry.payloadType)
                    .put("mimeType", entry.mimeType)
                    .put("htmlContent", entry.htmlContent)
                    .put("payloadData", entry.payloadData)
                    .put("thumbnail", entry.thumbnail)
                    .put("filesJson", entry.filesJson)
                    .put("blobId", entry.blobId)
                    .put("blobSize", entry.blobSize)
                    .put("blobReady", entry.blobReady)
            )
        }
        secureStore.put(SecureSecretStore.HISTORY_BACKUP, payload.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    fun load(): List<ClipboardEntry> {
        val encrypted = secureStore.get(SecureSecretStore.HISTORY_BACKUP)
        val legacy = prefs.getString(KEY_ENTRIES, null)
        val json = encrypted?.toString(Charsets.UTF_8) ?: legacy ?: "[]"
        if (encrypted == null && legacy != null) {
            secureStore.put(SecureSecretStore.HISTORY_BACKUP, legacy.toByteArray(Charsets.UTF_8))
            prefs.edit().remove(KEY_ENTRIES).apply()
        }
        val payload = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until payload.length()) {
                val item = payload.optJSONObject(index) ?: continue
                val content = item.optString("content")
                if (content.isBlank()) continue
                add(
                    ClipboardEntry(
                        content = content,
                        source = item.optString("source", "LOCAL"),
                        sourceApp = item.optString("sourceApp"),
                        sourceTitle = item.optString("sourceTitle"),
                        sourceIcon = item.optString("sourceIcon"),
                        timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                        pinned = item.optBoolean("pinned", false),
                        folder = item.optString("folder"),
                        payloadType = item.optString("payloadType", "text"),
                        mimeType = item.optString("mimeType", "text/plain"),
                        htmlContent = item.optString("htmlContent"),
                        payloadData = item.optString("payloadData"),
                        thumbnail = item.optString("thumbnail"),
                        filesJson = item.optString("filesJson", "[]"),
                        blobId = item.optString("blobId"),
                        blobSize = item.optLong("blobSize"),
                        blobReady = item.optBoolean("blobReady", true)
                    )
                )
            }
        }
    }

    fun clear() {
        secureStore.remove(SecureSecretStore.HISTORY_BACKUP)
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    companion object {
        private const val PREFS_NAME = "fastpaste_history_backup"
        private const val KEY_ENTRIES = "entries"
    }
}
