package com.fastpaste.app.sync

import android.content.Context
import com.fastpaste.app.data.ClipboardEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class DeletedHistoryStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markDeleted(text: String, deletedAt: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return

        val textHash = hashText(text)
        val markers = loadMarkers()
            .filterNot { it.textHash == textHash }
            .toMutableList()
        markers += DeletedMarker(textHash = textHash, deletedAt = deletedAt)
        saveMarkers(markers.sortedByDescending { it.deletedAt }.take(MAX_DELETED_MARKERS))
    }

    fun markCleared(entries: List<ClipboardEntry>, clearedAt: Long = System.currentTimeMillis()) {
        entries.forEach { markDeleted(it.content, clearedAt) }
        prefs.edit().putLong(KEY_CLEAR_AT, clearedAt).apply()
    }

    fun isDeleted(text: String, timestamp: Long): Boolean {
        if (text.isBlank()) return false

        val clearAt = prefs.getLong(KEY_CLEAR_AT, 0L)
        if (clearAt > 0L && timestamp <= clearAt) return true

        val textHash = hashText(text)
        return loadMarkers().any { it.textHash == textHash && timestamp <= it.deletedAt }
    }

    fun hasMarker(text: String): Boolean {
        if (text.isBlank()) return false

        val textHash = hashText(text)
        return loadMarkers().any { it.textHash == textHash }
    }

    private fun loadMarkers(): List<DeletedMarker> {
        val json = prefs.getString(KEY_MARKERS, "[]").orEmpty()
        val array = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        val markers = mutableListOf<DeletedMarker>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val hash = item.optString("hash")
            val deletedAt = item.optLong("deletedAt", 0L)
            if (hash.isNotBlank() && deletedAt > 0L) {
                markers += DeletedMarker(textHash = hash, deletedAt = deletedAt)
            }
        }
        return markers
    }

    private fun saveMarkers(markers: List<DeletedMarker>) {
        val array = JSONArray()
        markers.forEach { marker ->
            array.put(
                JSONObject()
                    .put("hash", marker.textHash)
                    .put("deletedAt", marker.deletedAt)
            )
        }
        prefs.edit().putString(KEY_MARKERS, array.toString()).apply()
    }

    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class DeletedMarker(
        val textHash: String,
        val deletedAt: Long
    )

    companion object {
        private const val PREFS_NAME = "fastpaste_deleted_history"
        private const val KEY_MARKERS = "markers"
        private const val KEY_CLEAR_AT = "clear_at"
        private const val MAX_DELETED_MARKERS = 1_000
    }
}
