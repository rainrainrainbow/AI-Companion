package com.ai.companion.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ai.companion.core.AiCompanionApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 微调服务 - 执行LoRA模型微调
 */
class FineTuneService : Service() {

    companion object {
        private const val TAG = "FineTuneService"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        val app = application as AiCompanionApp
        val llm = app.llmEngine

        // 使用协程而非Thread来调用suspend函数
        CoroutineScope(Dispatchers.IO).launch {
            try {
                llm.runFineTune { progress ->
                    Log.d(TAG, "Fine-tune progress: ${(progress * 100).toInt()}%")
                    updateNotification(progress)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fine-tune failed", e)
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AiCompanionApp.CHANNEL_FINETUNE)
            .setContentTitle("模型微调中")
            .setContentText("正在根据您的反馈优化AI...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        val notification = NotificationCompat.Builder(this, AiCompanionApp.CHANNEL_FINETUNE)
            .setContentTitle("模型微调中")
            .setContentText("进度: ${(progress * 100).toInt()}%")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}

/**
 * 定时微调广播接收器
 */
class FineTuneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("FineTuneAlarm", "Alarm triggered, starting fine-tune")
        val serviceIntent = Intent(context, FineTuneService::class.java)
        context.startForegroundService(serviceIntent)
    }
}