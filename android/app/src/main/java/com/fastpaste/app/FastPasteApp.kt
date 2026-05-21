package com.fastpaste.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.fastpaste.app.data.AppDatabase

class FastPasteApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fast Paste Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Clipboard sync service notification"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "fast_paste_service"
    }
}
