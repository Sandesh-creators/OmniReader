package com.omnireader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OmniReaderApp : Application() {
    companion object {
        const val TTS_CHANNEL_ID = "tts_channel"
        const val TTS_CHANNEL_NAME = "Text-to-Speech"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                TTS_CHANNEL_ID,
                TTS_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TTS playback controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
