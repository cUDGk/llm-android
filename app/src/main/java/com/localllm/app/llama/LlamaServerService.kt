package com.localllm.app.llama

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localllm.app.LocalLLMApp
import com.localllm.app.ui.MainActivity

/**
 * llama-server をバックグラウンドで常駐させるための Foreground Service。
 * 実際のプロセス制御ロジックは後続で追加する (今はスケルトンのみ)。
 */
class LlamaServerService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, LocalLLMApp.CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("LocalLLM")
            .setContentText("llama-server running")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
