package com.fastpaste.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_history")
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val source: String,   // "LOCAL" or "REMOTE"
    val sourceApp: String = "",
    val sourceTitle: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val folder: String = ""
)
