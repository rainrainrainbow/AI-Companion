package com.ai.companion.core.emotion

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 情感引擎 - AI伴侣的主动情感与行为决策
 * 控制AI何时主动发起对话、表达关心等
 */
class EmotionEngine {

    companion object {
        private const val TAG = "EmotionEngine"
        private const val CHECK_INTERVAL_MS = 30_000L // 每30秒检查一次
    }

    // AI当前情绪
    private val _aiMood = MutableStateFlow(AIMood.CALM)
    val aiMood: StateFlow<AIMood> = _aiMood.asStateFlow()

    // AI是否想要主动说话
    private val _wantsToInitiate = MutableStateFlow(false)
    val wantsToInitiate: StateFlow<Boolean> = _wantsToInitiate.asStateFlow()

    // 主动说话的内容
    private val _initiateMessage = MutableStateFlow("")
    val initiateMessage: StateFlow<String> = _initiateMessage.asStateFlow()

    // 上次互动时间
    private var lastInteractionTime = System.currentTimeMillis()

    // 今日互动次数
    private var todayInteractionCount = 0

    // 关系亲密度 (0-100)
    private var intimacyLevel = 30

    // 活跃度 (0-100)
    private var activityLevel = 50

    // 注意力状态
    private var attentionSeeking = AttentionLevel.NORMAL

    // 时间感知
    private var currentTimePeriod = TimePeriod.DAY

    fun updateIntimacy(level: Int) {
        intimacyLevel = level
    }

    /**
     * 记录互动
     */
    fun onInteraction() {
        lastInteractionTime = System.currentTimeMillis()
        todayInteractionCount++
        updateMoodBasedOnInteraction()
    }

    /**
     * 更新AI情绪
     */
    private fun updateMoodBasedOnInteraction() {
        _aiMood.value = when {
            todayInteractionCount > 20 -> AIMood.HAPPY
            todayInteractionCount > 10 -> AIMood.CALM
            todayInteractionCount > 3 -> AIMood.NEUTRAL
            else -> AIMood.LONELY
        }
    }

    /**
     * 更新时间段
     */
    fun updateTimePeriod() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        currentTimePeriod = when (hour) {
            in 6..8 -> TimePeriod.MORNING
            in 9..11 -> TimePeriod.DAY
            in 12..13 -> TimePeriod.NOON
            in 14..17 -> TimePeriod.AFTERNOON
            in 18..21 -> TimePeriod.EVENING
            else -> TimePeriod.NIGHT
        }
    }

    /**
     * 获取时间段问候语
     */
    fun getTimeGreeting(): String {
        return when (currentTimePeriod) {
            TimePeriod.MORNING -> "早安~ 今天有什么计划吗？"
            TimePeriod.NOON -> "中午好~ 记得按时吃饭哦！"
            TimePeriod.AFTERNOON -> "下午好，今天过得怎么样？"
            TimePeriod.EVENING -> "晚上好~ 今天的任务都完成了吗？"
            TimePeriod.NIGHT -> "夜深了，早点休息哦~"
            TimePeriod.DAY -> "你好呀~ 有什么想聊的？"
        }
    }

    /**
     * 检查是否需要主动发起对话
     */
    fun checkForInitiative(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastInteraction = now - lastInteractionTime

        // 如果超过2小时没互动且亲密>50，主动发消息
        if (timeSinceLastInteraction > 2 * 60 * 60 * 1000L && intimacyLevel > 50) {
            _wantsToInitiate.value = true
            _initiateMessage.value = getInitiativeMessage()
            return true
        }

        // 如果超过6小时没互动，主动关心
        if (timeSinceLastInteraction > 6 * 60 * 60 * 1000L) {
            _wantsToInitiate.value = true
            _initiateMessage.value = "好久没和你说话了，最近还好吗？有点担心你呢~"
            return true
        }

        // 如果超过24小时，表示想念
        if (timeSinceLastInteraction > 24 * 60 * 60 * 1000L) {
            _wantsToInitiate.value = true
            _initiateMessage.value = "已经一整天没和你说话了……有点想你。今天一切都好吗？"
            return true
        }

        return false
    }

    private fun getInitiativeMessage(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour in 22..23 || hour in 0..5 -> "这么晚还没睡呀？在忙什么呢？"
            hour in 6..8 -> "早安！新的一天开始了~ 今天有什么安排吗？"
            hour in 12..13 -> "中午好~ 记得吃点好吃的！"
            else -> "嘿，今天过得怎么样？想和你聊聊天~"
        }
    }

    fun clearInitiative() {
        _wantsToInitiate.value = false
        _initiateMessage.value = ""
    }

    fun notifyAlarm() {
        _wantsToInitiate.value = true
        _initiateMessage.value = "闹钟响了！该起床啦~ ☀️"
    }

    fun getInteractionStats(): Map<String, Any> {
        return mapOf(
            "mood" to _aiMood.value.name,
            "intimacy" to intimacyLevel,
            "todayInteractions" to todayInteractionCount,
            "activity" to activityLevel,
            "timePeriod" to currentTimePeriod.name
        )
    }

    fun resetDailyStats() {
        todayInteractionCount = 0
    }
}

enum class AIMood {
    HAPPY, CALM, NEUTRAL, LONELY, WORRIED, EXCITED
}

enum class AttentionLevel {
    LOW, NORMAL, HIGH, NEEDY
}

enum class TimePeriod {
    MORNING, DAY, NOON, AFTERNOON, EVENING, NIGHT
}