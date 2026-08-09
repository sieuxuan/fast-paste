package com.fastpaste.app.data

import android.content.Context
import android.annotation.SuppressLint
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fastpaste.app.security.SecureSecretStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileInputStream

internal object SqlCipherMigration {
    private const val DATABASE_NAME = "fast_paste.db"
    private const val BACKUP_NAME = "fast_paste.plaintext-migration.db"
    private const val PREFS_NAME = "fastpaste_database_migration"
    private const val KEY_COMPLETE = "sqlcipher_v1_complete"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @SuppressLint("ApplySharedPref") // Synchronous commits are migration crash barriers.
    fun open(context: Context): AppDatabase {
        val appContext = context.applicationContext
        System.loadLibrary("sqlcipher")
        val passphrase = SecureSecretStore(appContext)
            .getOrCreate(SecureSecretStore.DATABASE_KEY)
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
        val backupFile = File(databaseFile.parentFile, BACKUP_NAME)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_COMPLETE, false) && backupFile.exists()) {
            backupFile.delete()
        }

        if (isPlaintext(databaseFile)) {
            checkpointPlaintext(databaseFile)
            backupFile.delete()
            check(databaseFile.renameTo(backupFile)) {
                "Không tạo được bản sao an toàn trước khi mã hoá lịch sử."
            }
            sidecar(databaseFile, "-wal").delete()
            sidecar(databaseFile, "-shm").delete()
            prefs.edit().putBoolean(KEY_COMPLETE, false).commit()
        }

        if (backupFile.exists() && !prefs.getBoolean(KEY_COMPLETE, false)) {
            deleteDatabaseFiles(databaseFile)
            val encrypted = buildEncrypted(appContext, passphrase)
            try {
                importPlaintext(backupFile, encrypted.openHelper.writableDatabase)
                check(countPlaintext(backupFile) == countEncrypted(encrypted.openHelper.writableDatabase)) {
                    "Đối chiếu SQLCipher thất bại; bản Room cũ được giữ nguyên."
                }
                check(prefs.edit().putBoolean(KEY_COMPLETE, true).commit())
                backupFile.delete()
                return encrypted
            } catch (error: Throwable) {
                encrypted.close()
                deleteDatabaseFiles(databaseFile)
                check(backupFile.renameTo(databaseFile)) {
                    "Không phục hồi được Room cũ sau lỗi SQLCipher: ${error.message}"
                }
                throw IllegalStateException("Không migrate được lịch sử sang SQLCipher.", error)
            }
        }

        return buildEncrypted(appContext, passphrase).also {
            // Force opening now so a wrong/lost Keystore secret fails during
            // startup, not later in a background clipboard callback.
            it.openHelper.writableDatabase.query("SELECT 1").close()
            prefs.edit().putBoolean(KEY_COMPLETE, true).apply()
        }
    }

    private fun buildEncrypted(context: Context, passphrase: ByteArray): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()

    private fun checkpointPlaintext(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount >= 3) {
                    check(cursor.getInt(0) == 0 && cursor.getInt(1) == cursor.getInt(2)) {
                        "Room đang bận; chưa thể mã hoá lịch sử an toàn."
                    }
                }
            }
        } finally {
            database.close()
        }
    }

    private fun importPlaintext(file: File, target: SupportSQLiteDatabase) {
        val source = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            source.rawQuery("SELECT * FROM clipboard_history ORDER BY id", null).use { cursor ->
                target.beginTransaction()
                try {
                    while (cursor.moveToNext()) {
                        target.execSQL(
                            """INSERT OR REPLACE INTO clipboard_history
                            (id, content, source, sourceApp, sourceTitle, sourceIcon, timestamp,
                             pinned, folder, payloadType, mimeType, htmlContent, payloadData,
                             thumbnail, filesJson, blobId, blobSize, blobReady)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                            arrayOf(
                                cursor.long("id", 0L),
                                cursor.string("content", ""),
                                cursor.string("source", "LOCAL"),
                                cursor.string("sourceApp", ""),
                                cursor.string("sourceTitle", ""),
                                cursor.string("sourceIcon", ""),
                                cursor.long("timestamp", System.currentTimeMillis()),
                                cursor.long("pinned", 0L),
                                cursor.string("folder", ""),
                                cursor.string("payloadType", "text"),
                                cursor.string("mimeType", "text/plain"),
                                cursor.string("htmlContent", ""),
                                cursor.string("payloadData", ""),
                                cursor.string("thumbnail", ""),
                                cursor.string("filesJson", "[]"),
                                cursor.string("blobId", ""),
                                cursor.long("blobSize", 0L),
                                cursor.long("blobReady", 1L)
                            )
                        )
                    }
                    target.setTransactionSuccessful()
                } finally {
                    target.endTransaction()
                }
            }
        } finally {
            source.close()
        }
    }

    private fun countPlaintext(file: File): Long {
        val source = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            source.rawQuery("SELECT COUNT(*) FROM clipboard_history", null).use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } finally {
            source.close()
        }
    }

    private fun countEncrypted(database: SupportSQLiteDatabase): Long =
        database.query("SELECT COUNT(*) FROM clipboard_history").use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    private fun Cursor.string(column: String, fallback: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else fallback
    }

    private fun Cursor.long(column: String, fallback: Long): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else fallback
    }

    internal fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_HEADER.size) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(SQLITE_HEADER.size)
                input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(false)
    }

    private fun sidecar(file: File, suffix: String) = File(file.absolutePath + suffix)

    private fun deleteDatabaseFiles(file: File) {
        file.delete()
        sidecar(file, "-wal").delete()
        sidecar(file, "-shm").delete()
    }
}
