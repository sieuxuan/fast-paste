package com.fastpaste.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "clipboard_history", indices = [Index("blobId"), Index("content"), Index("timestamp"), Index(value = ["pinned", "timestamp"])])
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val source: String,   // "LOCAL" or "REMOTE"
    val sourceApp: String = "",
    val sourceTitle: String = "",
    val sourceIcon: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val folder: String = "",
    val payloadType: String = "text",
    val mimeType: String = "text/plain",
    val htmlContent: String = "",
    val payloadData: String = "",
    val thumbnail: String = "",
    val filesJson: String = "[]",
    val blobId: String = "",
    val blobSize: Long = 0L,
    val blobReady: Boolean = true
)
