package com.localllm.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class LocalLLMApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER,
                "Llama Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background llama-server process"
            }
        )
    }

    companion object {
        const val CHANNEL_SERVER = "llama_server"
    }
}
