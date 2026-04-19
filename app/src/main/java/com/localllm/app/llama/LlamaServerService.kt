package com.localllm.app.llama

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localllm.app.LocalLLMApp
import com.localllm.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * アプリをフォアグラウンド扱いにして、推論中に OS から殺されにくくする。
 * 旧実装では llama-server を子プロセスで spawn/監視していたが、Android 12+ の
 * phantom-process-killer で定期的に kill されるため、JNI in-process 方式に移行。
 * 本サービスは通知の維持と LLMEngine の state 表示のみを担当する。
 */
class LlamaServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground(status = "starting")
        watchJob = scope.launch {
            LLMEngine.state.collectLatest { state ->
                updateNotification(stateText(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 明示停止: JNI engine を unload してサービス終了
                scope.launch {
                    LLMEngine.unloadModel()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        watchJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- notification helpers ------------------------------------------------

    private fun startAsForeground(status: String) {
        val notif = buildNotification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(NOTIF_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LlamaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, LocalLLMApp.CHANNEL_SERVER)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LocalLLM")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPi,
            )
            .build()
    }

    private fun stateText(state: LLMEngine.State): String = when (state) {
        LLMEngine.State.Uninitialized -> "idle"
        LLMEngine.State.Initializing  -> "initializing…"
        LLMEngine.State.Initialized   -> "initialized"
        LLMEngine.State.LoadingModel  -> "loading model…"
        LLMEngine.State.ModelReady    -> "ready"
        LLMEngine.State.Processing    -> "processing prompt…"
        LLMEngine.State.Generating    -> "generating…"
        is LLMEngine.State.Error      -> "error: ${state.message}"
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.localllm.app.STOP_SERVER"

        fun start(context: Context) {
            val i = Intent(context, LlamaServerService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, LlamaServerService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
