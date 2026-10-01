package com.fastpaste.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipboardEntry::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                SqlCipherMigration.open(context)
                    .also { INSTANCE = it }
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN sourceApp TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN sourceTitle TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN payloadType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'text/plain'")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN htmlContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN payloadData TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN filesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN sourceIcon TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN thumbnail TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN blobId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN blobSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN blobReady INTEGER NOT NULL DEFAULT 1")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_history_blobId ON clipboard_history (blobId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_history_content ON clipboard_history (content)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_history_timestamp ON clipboard_history (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clipboard_history_pinned_timestamp ON clipboard_history (pinned, timestamp)")
            }
        }
    }
}
