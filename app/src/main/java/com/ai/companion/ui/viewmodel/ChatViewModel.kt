package com.ai.companion.ui.viewmodel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.companion.core.AiCompanionApp
import com.ai.companion.core.emotion.EmotionEngine
import com.ai.companion.core.llm.ChatMessage
import com.ai.companion.core.llm.Role
import com.ai.companion.core.memory.MemoryEngine
import com.ai.companion.service.CompanionService
import com.ai.companion.service.FineTuneAlarmReceiver
import com.ai.companion.service.FineTuneService
import com.ai.companion.service.ACTION_START
import com.ai.companion.service.ACTION_STOP
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val inputText: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val modelLoaded: Boolean = false,
    val emotion: String = "NEUTRAL",
    val intimacyLevel: Int = 30,
    val todayInteractions: Int = 0,
    val feedbackCount: Int = 0,
    val feedbackThreshold: Int = 20,
    val showAvatar: Boolean = true,
    val ttsEnabled: Boolean = true,
    val autoFineTune: Boolean = true,
    val showSettings: Boolean = false,
    val showModelManager: Boolean = false,
    val serviceRunning: Boolean = false,
    val darkTheme: Boolean = false
)

class ChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PREFS_NAME = "ai_companion_prefs"
        private const val KEY_DARK_THEME = "dark_theme"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var memoryEngine: MemoryEngine? = null
    private var emotionEngine: EmotionEngine? = null

    private val app get() = AiCompanionApp.instance

    init {
        memoryEngine = MemoryEngine(app)
        emotionEngine = EmotionEngine()

        // 恢复暗黑主题偏好
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDarkTheme = prefs.getBoolean(KEY_DARK_THEME, false)
        _uiState.update { it.copy(darkTheme = savedDarkTheme) }

        // 监听AI状态
        viewModelScope.launch {
            app.llmEngine.isThinking.collect { thinking ->
                _uiState.update { it.copy(isThinking = thinking) }
            }
        }

        viewModelScope.launch {
            app.llmEngine.modelLoaded.collect { loaded ->
                _uiState.update { it.copy(modelLoaded = loaded) }
            }
        }

        viewModelScope.launch {
            app.llmEngine.responseStream.collect { token ->
            }
        }

        viewModelScope.launch {
            while (true) {
                delay(5000)
                val intimacy = memoryEngine?.intimacyLevel ?: 0
                val interactions = emotionEngine?.getInteractionStats()?.get("todayInteractions") as? Int ?: 0
                emotionEngine?.updateIntimacy(intimacy)
                _uiState.update {
                    it.copy(
                        intimacyLevel = intimacy,
                        todayInteractions = interactions,
                        feedbackCount = app.llmEngine.getFeedbackCount(),
                        emotion = emotionEngine?.aiMood?.value?.name ?: "NEUTRAL"
                    )
                }
            }
        }
    }

    fun loadModel() {
        viewModelScope.launch {
            app.llmEngine.loadModel()
        }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(Role.USER, text),
                inputText = ""
            )
        }

        viewModelScope.launch {
            emotionEngine?.onInteraction()
            memoryEngine?.extractAndStore(text, "")

            val response = app.llmEngine.generateResponse(text)
            _uiState.update {
                it.copy(messages = it.messages + ChatMessage(Role.ASSISTANT, response))
            }

            if (_uiState.value.ttsEnabled) {
                _uiState.update { it.copy(isSpeaking = true) }
                app.ttsEngine.speakWithEmotion(response, _uiState.value.emotion)
                delay(200)
                _uiState.update { it.copy(isSpeaking = false) }
            }

            updateAvatarEmotion()
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun startVoiceInput() {
        app.sttEngine.startListening()
        _uiState.update { it.copy(isListening = true) }

        viewModelScope.launch {
            app.sttEngine.recognizedText.collect { text ->
                if (text.isNotEmpty()) {
                    _uiState.update { it.copy(inputText = text, isListening = false) }
                }
            }
        }
    }

    fun stopVoiceInput() {
        app.sttEngine.stopListening()
        _uiState.update { it.copy(isListening = false) }
    }

    fun startFineTune(context: Context) {
        val intent = Intent(context, FineTuneService::class.java)
        context.startForegroundService(intent)
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun toggleModelManager() {
        _uiState.update { it.copy(showModelManager = !it.showModelManager, showSettings = false) }
    }

    fun toggleAvatar() {
        _uiState.update { it.copy(showAvatar = !it.showAvatar) }
    }

    fun toggleTTS() {
        _uiState.update { it.copy(ttsEnabled = !it.ttsEnabled) }
    }

    fun toggleDarkTheme() {
        val newValue = !_uiState.value.darkTheme
        _uiState.update { it.copy(darkTheme = newValue) }
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DARK_THEME, newValue).apply()
    }

    fun toggleAutoFineTune() {
        val newValue = !_uiState.value.autoFineTune
        _uiState.update { it.copy(autoFineTune = newValue) }

        if (newValue) {
            scheduleNightlyFineTune(app)
        }
    }

    fun startCompanionService(context: Context) {
        val intent = Intent(context, CompanionService::class.java).apply {
            action = ACTION_START
        }
        context.startForegroundService(intent)
        _uiState.update { it.copy(serviceRunning = true) }
    }

    fun toggleCompanionService(context: Context) {
        val newState = !_uiState.value.serviceRunning
        val intent = Intent(context, CompanionService::class.java).apply {
            action = if (newState) ACTION_START else ACTION_STOP
        }
        if (newState) {
            context.startForegroundService(intent)
        } else {
            context.stopService(intent)
        }
        _uiState.update { it.copy(serviceRunning = newState) }
    }

    private fun scheduleNightlyFineTune(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FineTuneAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (before(java.util.Calendar.getInstance())) {
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
        Log.d(TAG, "Nightly fine-tune scheduled at 2:00 AM")
    }

    private fun updateAvatarEmotion() {
    }

    override fun onCleared() {
        super.onCleared()
        app.llmEngine.release()
        app.sttEngine.release()
        app.ttsEngine.release()
    }
}