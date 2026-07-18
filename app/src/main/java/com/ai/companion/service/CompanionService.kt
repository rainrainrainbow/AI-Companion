package com.ai.companion.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ai.companion.core.AiCompanionApp
import com.ai.companion.ui.MainActivity

/**
 * 伴侣后台服务 - 保持AI在后台运行、主动发起对话、定时检查
 */
class CompanionService : Service() {

    companion object {
        private const val TAG = "CompanionService"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CompanionService created")
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground(NOTIFICATION_ID, createNotification())
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, AiCompanionApp.CHANNEL_COMPANION)
            .setContentTitle("星璃")
            .setContentText("我在这里陪着你~")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CompanionService destroyed")
    }

    fun interface CompanionBinder {
        fun getService(): CompanionService
    }

    object Actions {
        const val ACTION_START = "com.ai.companion.action.START"
        const val ACTION_STOP = "com.ai.companion.action.STOP"
    }
}

const val ACTION_START = "com.ai.companion.action.START"
const val ACTION_STOP = "com.ai.companion.action.STOP"