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

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentOnce(limit: Int): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<ClipboardEntry>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestOnce(): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ClipboardEntry?

    @Query("SELECT * FROM clipboard_history WHERE content = :content ORDER BY timestamp DESC LIMIT 1")
    suspend fun getByContent(content: String): ClipboardEntry?

    @Query("UPDATE clipboard_history SET pinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    @Query("UPDATE clipboard_history SET source = :source, timestamp = :timestamp, pinned = :pinned, folder = :folder WHERE id = :id")
    suspend fun updateEntryById(
        id: Long,
        source: String,
        timestamp: Long,
        pinned: Boolean,
        folder: String
    )

    @Query("DELETE FROM clipboard_history WHERE content = :content AND id != :keepId")
    suspend fun deleteDuplicatesByContent(content: String, keepId: Long): Int

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun clearAll()
}
