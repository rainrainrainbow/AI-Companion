package com.ai.companion.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ai.companion.core.llm.LocalLLMEngine
import com.ai.companion.core.audio.STTEngine
import com.ai.companion.core.audio.TTSEngine

class AiCompanionApp : Application() {

    lateinit var llmEngine: LocalLLMEngine
        private set
    lateinit var sttEngine: STTEngine
        private set
    lateinit var ttsEngine: TTSEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        initEngines()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val companionChannel = NotificationChannel(
                CHANNEL_COMPANION,
                "AI伴侣服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI伴侣后台服务运行状态"
            }

            val finetuneChannel = NotificationChannel(
                CHANNEL_FINETUNE,
                "模型微调",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "模型微调进度通知"
            }

            val chatChannel = NotificationChannel(
                CHANNEL_CHAT,
                "聊天消息",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "AI伴侣主动发来的消息"
            }

            manager.createNotificationChannel(companionChannel)
            manager.createNotificationChannel(finetuneChannel)
            manager.createNotificationChannel(chatChannel)
        }
    }

    private fun initEngines() {
        llmEngine = LocalLLMEngine(this)
        sttEngine = STTEngine(this)
        ttsEngine = TTSEngine(this)
    }

    companion object {
        lateinit var instance: AiCompanionApp
            private set

        const val CHANNEL_COMPANION = "companion_service"
        const val CHANNEL_FINETUNE = "model_finetune"
        const val CHANNEL_CHAT = "chat_messages"
    }
}
