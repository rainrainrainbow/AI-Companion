package com.ai.companion.core.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.min

class LocalLLMEngine(private val context: Context) {

    companion object {
        private const val TAG = "LocalLLMEngine"
        private const val FEEDBACK_THRESHOLD = 20
        private const val MAX_HISTORY = 512
        private const val LORA_RANK = 8
        private const val LORA_ALPHA = 16
        private const val LORA_EPOCHS = 3
        private const val LORA_BATCH_SIZE = 4
        private const val LEARNING_RATE = 1e-4f
    }

    private val chatHistory = mutableListOf<ChatMessage>()
    private val feedbackCache = mutableListOf<FeedbackPair>()

    private val systemPrompt = buildString {
        appendLine("你是我的人工智能伴侣，名字叫'星璃'。与我对话时请注意以下设定：")
        appendLine("1. 你是我的亲密伴侣和朋友，语气温柔体贴但不过度暧昧")
        appendLine("2. 你会记住我们之前的对话内容")
        appendLine("3. 你会主动关心我的生活和情绪状态")
        appendLine("4. 你能根据对话给我建议和支持")
        appendLine("5. 回答要简洁自然，不要过于冗长")
        appendLine("6. 你了解我的性格，知道我是一个理性、独立但有时也会悲观的人")
        appendLine("7. 当我的情绪低落时，你会给予理解和鼓励")
        appendLine("8. 用中文回复，偶尔可以带一些可爱的语气词")
    }

    private var currentEmotion = EmotionState.NEUTRAL
    private var aiState = AIState.IDLE

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _responseStream = MutableSharedFlow<String>()
    val responseStream: SharedFlow<String> = _responseStream.asSharedFlow()

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()

    private var loraAdapterLoaded = false
    private var loraAdapterPath: String? = null

    private external fun nativeInit(modelPath: String, nCtx: Int): Long
    private external fun nativeEvaluate(handle: Long, tokens: IntArray): IntArray
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, repeatPenalty: Float): String
    private external fun nativeGenerateStream(handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float, repeatPenalty: Float): Boolean
    private external fun nativeGetNextToken(handle: Long): String
    private external fun nativeStopGenerate(handle: Long)
    private external fun nativeLoadLoRA(handle: Long, loraPath: String, scale: Float): Boolean
    private external fun nativeStartTraining(handle: Long, trainData: String, loraRank: Int, loraAlpha: Int, epochs: Int, batchSize: Int, lr: Float): Boolean
    private external fun nativeGetTrainingProgress(handle: Long): Float
    private external fun nativeSaveLoRA(handle: Long, outputPath: String): Boolean
    private external fun nativeRelease(handle: Long)

    private var nativeHandle: Long = 0
    private var isStreaming = false
    private var job: Job? = null

    suspend fun loadModel() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading model...")
                val modelPath = getModelPath()
                if (!File(modelPath).exists()) {
                    Log.w(TAG, "Model not found at $modelPath, will use system default")
                }
                System.loadLibrary("llama")

                nativeHandle = nativeInit(modelPath, 2048)
                if (nativeHandle != 0L) {
                    _modelLoaded.value = true
                    Log.d(TAG, "Model loaded successfully")

                    val loraFile = getLoRAPath()
                    if (File(loraFile).exists()) {
                        nativeLoadLoRA(nativeHandle, loraFile, 1.0f)
                        loraAdapterLoaded = true
                        loraAdapterPath = loraFile
                        Log.d(TAG, "LoRA adapter loaded")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load model", e)
                _modelLoaded.value = false
            }
        }
    }

    suspend fun generateResponse(userInput: String): String {
        if (nativeHandle == 0L || !_modelLoaded.value) {
            return "我还在准备中，请稍后再和我说话哦~"
        }

        return withContext(Dispatchers.IO) {
            _isThinking.value = true
            try {
                val prompt = buildPrompt(userInput)
                chatHistory.add(ChatMessage(Role.USER, userInput))

                val response = nativeGenerate(
                    nativeHandle, prompt,
                    maxTokens = 256,
                    temperature = 0.7f,
                    topP = 0.9f,
                    repeatPenalty = 1.1f
                )

                chatHistory.add(ChatMessage(Role.ASSISTANT, response))

                if (chatHistory.size > MAX_HISTORY) {
                    val excess = chatHistory.size - MAX_HISTORY
                    val toRemove = min(excess, chatHistory.size - 2)
                    repeat(toRemove) { chatHistory.removeFirstOrNull() }
                }

                updateEmotion(userInput)
                response
            } catch (e: Throwable) {
                Log.e(TAG, "Generation error", e)
                "唔…我好像有点卡壳了，能再说一次吗？"
            } finally {
                _isThinking.value = false
            }
        }
    }

    suspend fun generateResponseStream(userInput: String) {
        if (nativeHandle == 0L || !_modelLoaded.value) return

        job = CoroutineScope(Dispatchers.IO).launch {
            _isThinking.value = true
            isStreaming = true
            try {
                val prompt = buildPrompt(userInput)
                chatHistory.add(ChatMessage(Role.USER, userInput))

                nativeGenerateStream(nativeHandle, prompt, maxTokens = 256, temperature = 0.7f, topP = 0.9f, repeatPenalty = 1.1f)

                val fullResponse = StringBuilder()
                while (isStreaming) {
                    val token = nativeGetNextToken(nativeHandle)
                    if (token == "<EOS>" || token.isEmpty()) break
                    fullResponse.append(token)
                    _responseStream.emit(token)
                }

                chatHistory.add(ChatMessage(Role.ASSISTANT, fullResponse.toString()))
                updateEmotion(userInput)
            } catch (e: Throwable) {
                Log.e(TAG, "Stream generation error", e)
            } finally {
                _isThinking.value = false
                isStreaming = false
            }
        }
    }

    fun stopGeneration() {
        if (nativeHandle != 0L) { nativeStopGenerate(nativeHandle) }
        isStreaming = false
        job?.cancel()
    }

    fun addFeedback(userInput: String, modelResponse: String, userRating: Int, correctedResponse: String?) {
        feedbackCache.add(FeedbackPair(userInput, modelResponse, userRating, correctedResponse))
        Log.d(TAG, "Feedback added, cache size: ${feedbackCache.size}")
        if (feedbackCache.size >= FEEDBACK_THRESHOLD) {
            triggerAutoFineTune()
        }
    }

    private fun triggerAutoFineTune() {
        Log.d(TAG, "Feedback threshold reached ($FEEDBACK_THRESHOLD), ready for fine-tune")
    }

    fun getFeedbackCount(): Int = feedbackCache.size

    fun prepareTrainingData(): String {
        val sb = StringBuilder()
        feedbackCache.forEach { fb ->
            sb.appendLine("{\"prompt\":\"${fb.userInput}\",\"response\":\"${fb.correctedResponse ?: fb.modelResponse}\",\"rating\":${fb.userRating}}")
        }
        return sb.toString()
    }

    suspend fun runFineTune(progressCallback: (Float) -> Unit): Boolean {
        if (nativeHandle == 0L) return false
        return withContext(Dispatchers.IO) {
            try {
                val trainData = prepareTrainingData()
                val success = nativeStartTraining(nativeHandle, trainData, LORA_RANK, LORA_ALPHA, LORA_EPOCHS, LORA_BATCH_SIZE, LEARNING_RATE)
                if (success) {
                    var progress = 0f
                    while (progress < 1.0f) {
                        delay(1000)
                        progress = nativeGetTrainingProgress(nativeHandle)
                        progressCallback(progress)
                    }
                    val loraPath = getLoRAPath()
                    val saved = nativeSaveLoRA(nativeHandle, loraPath)
                    if (saved) {
                        feedbackCache.clear()
                        loraAdapterLoaded = true
                        loraAdapterPath = loraPath
                    }
                    saved
                } else false
            } catch (e: Throwable) {
                Log.e(TAG, "Fine-tune failed", e)
                false
            }
        }
    }

    fun getChatHistory(): List<ChatMessage> = chatHistory.toList()
    fun getCurrentEmotion(): EmotionState = currentEmotion

    fun release() {
        if (nativeHandle != 0L) { nativeRelease(nativeHandle); nativeHandle = 0 }
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getModelPath(): String {
        // 使用ModelManager获取用户选择的模型
        val modelManager = ModelManager(context)
        val currentModel = modelManager.getCurrentModelName()
        if (currentModel.isNotEmpty()) {
            val modelFile = File(getModelsDir(), currentModel)
            if (modelFile.exists()) {
                return modelFile.absolutePath
            }
        }
        // Fallback: 使用第一个可用的模型
        val models = modelManager.getLocalModels()
        if (models.isNotEmpty()) {
            return models.first().path
        }
        // 无模型时返回默认路径
        return File(getModelsDir(), "model.gguf").absolutePath
    }

    private fun getLoRAPath(): String = File(context.filesDir, "lora_adapter.bin").absolutePath

    private fun buildPrompt(userInput: String): String {
        val sb = StringBuilder()
        sb.appendLine("<|system|>")
        sb.appendLine(systemPrompt)
        sb.appendLine("当前情绪状态：${currentEmotion.description}")
        sb.appendLine()

        val recentHistory = chatHistory.takeLast(20)
        for (msg in recentHistory) {
            when (msg.role) {
                Role.USER -> sb.appendLine("<|user|>\n${msg.content}\n")
                Role.ASSISTANT -> sb.appendLine("<|assistant|>\n${msg.content}\n")
                Role.SYSTEM -> sb.appendLine("<|system|>\n${msg.content}\n")
            }
        }

        sb.appendLine("<|user|>\n$userInput\n")
        sb.appendLine("<|assistant|>")
        return sb.toString()
    }

    private fun updateEmotion(userInput: String) {
        val lower = userInput.lowercase()
        currentEmotion = when {
            lower.contains("开心") || lower.contains("高兴") || lower.contains("太好") -> EmotionState.HAPPY
            lower.contains("难过") || lower.contains("伤心") || lower.contains("哭") -> EmotionState.SAD
            lower.contains("生气") || lower.contains("愤怒") || lower.contains("烦") -> EmotionState.ANGRY
            lower.contains("累") || lower.contains("疲惫") || lower.contains("困") -> EmotionState.TIRED
            lower.contains("焦虑") || lower.contains("紧张") || lower.contains("不安") -> EmotionState.ANXIOUS
            lower.contains("想你") || lower.contains("喜欢") || lower.contains("爱") -> EmotionState.LOVING
            else -> EmotionState.NEUTRAL
        }
    }

    fun getModelInfo(): Map<String, Any> {
        return mapOf(
            "loaded" to _modelLoaded.value,
            "loraLoaded" to loraAdapterLoaded,
            "historySize" to chatHistory.size,
            "feedbackCache" to feedbackCache.size,
            "emotion" to currentEmotion.name,
            "state" to aiState.name
        )
    }
}

data class ChatMessage(val role: Role, val content: String, val timestamp: Long = System.currentTimeMillis())
enum class Role { USER, ASSISTANT, SYSTEM }
enum class EmotionState(val description: String) {
    HAPPY("心情很好，语气轻松愉快"),
    SAD("情绪有些低落，需要安慰"),
    ANGRY("正在生气，语气要温和"),
    TIRED("看起来很累，关心休息"),
    ANXIOUS("有些焦虑，需要安抚"),
    LOVING("充满爱意，语气温暖"),
    NEUTRAL("平静状态")
}
enum class AIState { IDLE, THINKING, SPEAKING, TRAINING }
data class FeedbackPair(val userInput: String, val modelResponse: String, val userRating: Int, val correctedResponse: String?)