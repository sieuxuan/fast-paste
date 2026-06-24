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

    @Query("SELECT COUNT(*) FROM clipboard_history WHERE content = :content")
    suspend fun countByContent(content: String): Int

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun clearAll()
}
