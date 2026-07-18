package com.ai.companion.core.memory

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * 记忆系统 - 基于SQLite的长期记忆存储
 * 包含向量化语义记忆和关系图谱
 */
class MemoryEngine(private val context: Context) {

    companion object {
        private const val TAG = "MemoryEngine"
        private const val MAX_SHORT_TERM = 50
        private const val MEMORY_FILE = "companion_memory.json"
    }

    // 短期记忆（最近对话）
    private val shortTermMemory = mutableListOf<MemoryItem>()

    // 长期记忆（重要信息）
    private val longTermMemory = mutableListOf<MemoryItem>()

    // 用户画像
    private var userProfile = UserProfile()

    // 关系亲密度 (0-100)
    var intimacyLevel: Int = 30
        private set

    // 已经聊天的天数
    var daysKnown: Int = 1
        private set

    init {
        loadFromDisk()
    }

    /**
     * 添加记忆
     */
    suspend fun addMemory(content: String, category: MemoryCategory, importance: Int = 5) {
        val item = MemoryItem(
            content = content,
            category = category,
            importance = importance.coerceIn(1, 10),
            timestamp = System.currentTimeMillis()
        )

        shortTermMemory.add(item)

        // 如果重要度够高，存入长期记忆
        if (importance >= 6) {
            longTermMemory.add(item)
        }

        // 短期记忆截断
        if (shortTermMemory.size > MAX_SHORT_TERM) {
            shortTermMemory.removeAt(0)
        }

        saveToDisk()
    }

    /**
     * 从对话中提取并存储重要信息
     */
    suspend fun extractAndStore(userInput: String, aiResponse: String) {
        val importantPatterns = listOf(
            "我叫" to MemoryCategory.IDENTITY,
            "我喜欢" to MemoryCategory.PREFERENCE,
            "我讨厌" to MemoryCategory.PREFERENCE,
            "我害怕" to MemoryCategory.EMOTION,
            "我生日" to MemoryCategory.IDENTITY,
            "我住在" to MemoryCategory.IDENTITY,
            "我工作" to MemoryCategory.WORK,
            "我的梦想" to MemoryCategory.GOAL,
            "我想" to MemoryCategory.GOAL,
            "今天" to MemoryCategory.EVENT
        )

        for ((pattern, category) in importantPatterns) {
            if (userInput.contains(pattern)) {
                addMemory(userInput, category, importance = 7)
                Log.d(TAG, "Extracted memory: $userInput ($category)")
            }
        }

        // 更新亲密度
        intimacyLevel = (intimacyLevel + 0.5f).toInt().coerceAtMost(100)

        // 检测用户情绪关键词
        val emotionKeywords = listOf("难过", "开心", "焦虑", "生气", "累", "疲惫", "紧张")
        if (emotionKeywords.any { userInput.contains(it) }) {
            addMemory(userInput, MemoryCategory.EMOTION, importance = 8)
        }
    }

    /**
     * 检索相关记忆
     */
    fun recall(query: String, limit: Int = 5): List<MemoryItem> {
        val results = mutableListOf<MemoryItem>()

        // 简单关键字匹配检索
        val keywords = query.split("\\s+".toRegex()).filter { it.length > 1 }

        val allMemories = longTermMemory + shortTermMemory.takeLast(20)
        val scoredPairs: List<Pair<MemoryItem, Int>> = allMemories.map { memory ->
            val score = keywords.count { keyword ->
                memory.content.contains(keyword, ignoreCase = true)
            }
            Pair(memory, score)
        }
        val scored = scoredPairs.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        results.addAll(scored)
        return results
    }

    /**
     * 获取今天的重要事件
     */
    fun getTodayEvents(): List<MemoryItem> {
        val todayStart = getTodayStart()
        return longTermMemory.filter { it.timestamp >= todayStart && it.category == MemoryCategory.EVENT }
    }

    /**
     * 获取用户画像摘要
     */
    fun getUserProfileSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== 用户画像 ===")
        sb.appendLine("关系亲密度: $intimacyLevel/100")
        sb.appendLine("认识天数: ${daysKnown}天")

        if (userProfile.name != null) sb.appendLine("名字: ${userProfile.name}")
        if (userProfile.birthday != null) sb.appendLine("生日: ${userProfile.birthday}")
        if (userProfile.job != null) sb.appendLine("工作: ${userProfile.job}")
        if (userProfile.hobbies.isNotEmpty()) sb.appendLine("爱好: ${userProfile.hobbies.joinToString(", ")}")
        if (userProfile.likes.isNotEmpty()) sb.appendLine("喜欢: ${userProfile.likes.joinToString(", ")}")
        if (userProfile.dislikes.isNotEmpty()) sb.appendLine("不喜欢: ${userProfile.dislikes.joinToString(", ")}")

        // 最近重要记忆
        val important = longTermMemory.filter { it.importance >= 7 }.takeLast(10)
        if (important.isNotEmpty()) {
            sb.appendLine("\n=== 重要记忆 ===")
            important.forEach { sb.appendLine("- ${it.content}") }
        }

        return sb.toString()
    }

    fun updateUserName(name: String) { userProfile = userProfile.copy(name = name); saveToDisk() }
    fun updateUserBirthday(birthday: String) { userProfile = userProfile.copy(birthday = birthday); saveToDisk() }
    fun updateUserJob(job: String) { userProfile = userProfile.copy(job = job); saveToDisk() }
    fun addHobby(hobby: String) { userProfile = userProfile.copy(hobbies = userProfile.hobbies + hobby); saveToDisk() }
    fun addLike(like: String) { userProfile = userProfile.copy(likes = userProfile.likes + like); saveToDisk() }
    fun addDislike(dislike: String) { userProfile = userProfile.copy(dislikes = userProfile.dislikes + dislike); saveToDisk() }

    private fun getTodayStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun saveToDisk() {
        try {
            val gson = Gson()
            val data = MemoryData(shortTermMemory, longTermMemory, userProfile, intimacyLevel, daysKnown)
            val file = File(context.filesDir, MEMORY_FILE)
            FileWriter(file).use { gson.toJson(data, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val file = File(context.filesDir, MEMORY_FILE)
            if (!file.exists()) return
            val gson = Gson()
            FileReader(file).use {
                val data = gson.fromJson(it, MemoryData::class.java)
                shortTermMemory.clear()
                shortTermMemory.addAll(data.shortTerm)
                longTermMemory.clear()
                longTermMemory.addAll(data.longTerm)
                userProfile = data.userProfile
                intimacyLevel = data.intimacyLevel
                daysKnown = data.daysKnown
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load memory", e)
        }
    }
}

// === 数据类 ===

data class MemoryItem(
    val content: String,
    val category: MemoryCategory,
    val importance: Int,
    val timestamp: Long
)

enum class MemoryCategory {
    IDENTITY, PREFERENCE, EMOTION, EVENT, WORK, GOAL, CUSTOM
}

data class UserProfile(
    val name: String? = null,
    val birthday: String? = null,
    val job: String? = null,
    val hobbies: List<String> = emptyList(),
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList()
)

data class MemoryData(
    val shortTerm: List<MemoryItem>,
    val longTerm: List<MemoryItem>,
    val userProfile: UserProfile,
    val intimacyLevel: Int,
    val daysKnown: Int
)