package com.fastpaste.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipboardEntry::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fast_paste.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN sourceApp TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN sourceTitle TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN payloadType TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'text/plain'")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN htmlContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN payloadData TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clipboard_history ADD COLUMN filesJson TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
