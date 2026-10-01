package com.fastpaste.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Insert
    suspend fun insert(entry: ClipboardEntry)

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ClipboardEntry>>

    @Query("SELECT id, content, source, sourceApp, sourceTitle, sourceIcon, timestamp, pinned, folder, payloadType, mimeType, htmlContent, '' AS payloadData, thumbnail, filesJson, blobId, blobSize, blobReady FROM clipboard_history WHERE pinned = 1 OR id IN (SELECT id FROM clipboard_history WHERE pinned = 0 ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp DESC")
    fun getRecent(limit: Int): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_history WHERE pinned = 1 OR id IN (SELECT id FROM clipboard_history WHERE pinned = 0 ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp DESC")
    suspend fun getRecentOnce(limit: Int): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestOnce(): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE blobId = :blobId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getByBlobId(blobId: String): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE content = :content AND payloadType = 'text' ORDER BY pinned DESC, timestamp DESC LIMIT 1")
    suspend fun getByContent(content: String): ClipboardEntry?

    @Query("UPDATE clipboard_history SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    @Query("UPDATE clipboard_history SET source = :source, sourceApp = :sourceApp, sourceTitle = :sourceTitle, sourceIcon = :sourceIcon, timestamp = :timestamp, pinned = :pinned, folder = :folder, payloadType = :payloadType, mimeType = :mimeType, htmlContent = :htmlContent, payloadData = :payloadData, thumbnail = :thumbnail, filesJson = :filesJson, blobId = :blobId, blobSize = :blobSize, blobReady = :blobReady WHERE id = :id")
    suspend fun updateEntryById(
        id: Long,
        source: String,
        sourceApp: String,
        sourceTitle: String,
        sourceIcon: String,
        timestamp: Long,
        pinned: Boolean,
        folder: String,
        payloadType: String,
        mimeType: String,
        htmlContent: String,
        payloadData: String,
        thumbnail: String,
        filesJson: String,
        blobId: String,
        blobSize: Long,
        blobReady: Boolean
    )

    @Query("DELETE FROM clipboard_history WHERE content = :content AND payloadType = 'text' AND id != :keepId AND pinned = 0")
    suspend fun deleteDuplicatesByContent(content: String, keepId: Long): Int

    @Query("DELETE FROM clipboard_history WHERE blobId = :blobId AND id != :keepId AND pinned = 0")
    suspend fun deleteDuplicatesByBlobId(blobId: String, keepId: Long): Int

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history WHERE pinned = 0")
    suspend fun clearUnpinned(): Int

    @Query("DELETE FROM clipboard_history WHERE pinned = 0 AND id IN (:ids)")
    suspend fun deleteUnpinnedByIds(ids: List<Long>): Int

    @Query("UPDATE clipboard_history SET content = :content, blobId = :blobId, folder = :folder, source = 'LOCAL', timestamp = :timestamp WHERE id = :id")
    suspend fun updateEditedEntry(id: Long, content: String, blobId: String, folder: String, timestamp: Long)
}
