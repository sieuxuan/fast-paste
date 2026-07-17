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

    @Query("SELECT * FROM clipboard_history WHERE pinned = 1 OR id IN (SELECT id FROM clipboard_history WHERE pinned = 0 ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp DESC")
    fun getRecent(limit: Int): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_history WHERE pinned = 1 OR id IN (SELECT id FROM clipboard_history WHERE pinned = 0 ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp DESC")
    suspend fun getRecentOnce(limit: Int): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestOnce(): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE content = :content ORDER BY pinned DESC, timestamp DESC LIMIT 1")
    suspend fun getByContent(content: String): ClipboardEntry?

    @Query("UPDATE clipboard_history SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    @Query("UPDATE clipboard_history SET source = :source, sourceApp = :sourceApp, sourceTitle = :sourceTitle, timestamp = :timestamp, pinned = :pinned, folder = :folder, payloadType = :payloadType, mimeType = :mimeType, htmlContent = :htmlContent, payloadData = :payloadData, filesJson = :filesJson WHERE id = :id")
    suspend fun updateEntryById(
        id: Long,
        source: String,
        sourceApp: String,
        sourceTitle: String,
        timestamp: Long,
        pinned: Boolean,
        folder: String,
        payloadType: String,
        mimeType: String,
        htmlContent: String,
        payloadData: String,
        filesJson: String
    )

    @Query("DELETE FROM clipboard_history WHERE content = :content AND id != :keepId AND pinned = 0")
    suspend fun deleteDuplicatesByContent(content: String, keepId: Long): Int

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history WHERE pinned = 0")
    suspend fun clearUnpinned(): Int

    @Query("DELETE FROM clipboard_history WHERE pinned = 0 AND id IN (:ids)")
    suspend fun deleteUnpinnedByIds(ids: List<Long>): Int

    @Query("UPDATE clipboard_history SET content = :content, folder = :folder, source = 'LOCAL', timestamp = :timestamp, payloadType = 'text', mimeType = 'text/plain', htmlContent = '', payloadData = '', filesJson = '[]' WHERE id = :id")
    suspend fun updateEditedEntry(id: Long, content: String, folder: String, timestamp: Long)
}
