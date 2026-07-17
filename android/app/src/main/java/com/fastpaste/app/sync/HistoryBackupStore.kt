package com.fastpaste.app.sync

import android.content.Context
import com.fastpaste.app.data.ClipboardEntry
import org.json.JSONArray
import org.json.JSONObject

class HistoryBackupStore(context: Context) {
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
                    .put("timestamp", entry.timestamp)
                    .put("pinned", entry.pinned)
                    .put("folder", entry.folder)
                    .put("payloadType", entry.payloadType)
                    .put("mimeType", entry.mimeType)
                    .put("htmlContent", entry.htmlContent)
                    .put("payloadData", entry.payloadData)
                    .put("filesJson", entry.filesJson)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, payload.toString()).apply()
    }

    fun load(): List<ClipboardEntry> {
        val json = prefs.getString(KEY_ENTRIES, "[]").orEmpty()
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
                        timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                        pinned = item.optBoolean("pinned", false),
                        folder = item.optString("folder"),
                        payloadType = item.optString("payloadType", "text"),
                        mimeType = item.optString("mimeType", "text/plain"),
                        htmlContent = item.optString("htmlContent"),
                        payloadData = item.optString("payloadData"),
                        filesJson = item.optString("filesJson", "[]")
                    )
                )
            }
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    companion object {
        private const val PREFS_NAME = "fastpaste_history_backup"
        private const val KEY_ENTRIES = "entries"
    }
}
