package com.ai.companion.core.llm

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

/**
 * 模型管理器 - 扫描、导入、下载和管理GGUF模型文件
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val MODEL_INDEX = "model_index.json"
        private const val NATIVE_LIBS_DIR = "native_libs"

        // 推荐的模型下载源
        val RECOMMENDED_MODELS = listOf(
            ModelSource(
                name = "Qwen2.5-0.5B-Q4_K_M",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-GGUF/resolve/main/qwen2.5-0.5b-q4_k_m.gguf",
                size = "~350MB",
                description = "通义千问0.5B量化版，轻量级，适合手机运行"
            ),
            ModelSource(
                name = "Qwen2.5-1.5B-Q4_K_M",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-GGUF/resolve/main/qwen2.5-1.5b-q4_k_m.gguf",
                size = "~1GB",
                description = "通义千问1.5B量化版，性能更好，8GB内存以上推荐"
            ),
            ModelSource(
                name = "Gemma-2-2B-Q4_K_M",
                url = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
                size = "~1.4GB",
                description = "Google Gemma 2B，英文能力强，中文也可用"
            ),
            ModelSource(
                name = "Llama-3.2-1B-Q4_K_M",
                url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-q4_k_m.gguf",
                size = "~700MB",
                description = "Meta Llama 3.2 1B，轻量高效"
            )
        )
    }

    private val modelsDir: File get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    private val nativeLibsDir: File get() = File(context.filesDir, NATIVE_LIBS_DIR).also { it.mkdirs() }

    /**
     * 扫描已下载的模型文件
     */
    fun getLocalModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val files = modelsDir.listFiles() ?: return models
        for (file in files) {
            if (file.isFile && file.name.endsWith(".gguf")) {
                models.add(ModelInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    isLoaded = file.name == getCurrentModelName()
                ))
            }
        }
        return models.sortedByDescending { it.size }
    }

    /**
     * 扫描设备Download目录中的GGUF模型文件
     */
    fun scanDownloadDir(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val downloadDir = File(context.getExternalFilesDir(null)?.parentFile, "Download")
        if (!downloadDir.exists()) return models

        val files = downloadDir.listFiles() ?: return models
        for (file in files) {
            if (file.isFile && file.name.endsWith(".gguf")) {
                models.add(ModelInfo(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    isLoaded = false
                ))
            }
        }
        return models
    }

    /**
     * 从Download目录导入模型到应用内部目录
     */
    suspend fun importModel(sourcePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source file not found: $sourcePath")
                return@withContext false
            }
            val destFile = File(modelsDir, sourceFile.name)
            if (destFile.exists()) {
                Log.w(TAG, "Model already exists: ${sourceFile.name}")
                return@withContext true
            }
            sourceFile.copyTo(destFile, overwrite = false)
            Log.d(TAG, "Imported model: ${sourceFile.name} (${sourceFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import model", e)
            false
        }
    }

    /**
     * 下载模型
     */
    suspend fun downloadModel(url: String, fileName: String, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(modelsDir, fileName)
            if (destFile.exists()) {
                Log.w(TAG, "Model already exists: $fileName")
                return@withContext true
            }

            Log.d(TAG, "Downloading model from $url")
            val connection = URL(url).openConnection()
            connection.connect()
            val fileLength = connection.contentLengthLong
            val inputStream = connection.getInputStream()
            val outputStream = FileOutputStream(destFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (fileLength > 0) {
                    onProgress(totalBytesRead.toFloat() / fileLength)
                }
            }

            outputStream.close()
            inputStream.close()
            Log.d(TAG, "Model downloaded: $fileName (${totalBytesRead / 1024 / 1024}MB)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    /**
     * 删除模型
     */
    fun deleteModel(fileName: String): Boolean {
        val file = File(modelsDir, fileName)
        return if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted model: $fileName")
            true
        } else {
            Log.w(TAG, "Model not found: $fileName")
            false
        }
    }

    /**
     * 获取当前加载的模型名
     */
    fun getCurrentModelName(): String {
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        return prefs.getString("current_model", "qwen2.5-0.5b-q4_k_m.gguf") ?: "qwen2.5-0.5b-q4_k_m.gguf"
    }

    /**
     * 设置当前模型
     */
    fun setCurrentModel(fileName: String) {
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_model", fileName).apply()
        Log.d(TAG, "Current model set to: $fileName")
    }

    /**
     * 检查原生库是否可用
     */
    fun isNativeLibAvailable(): Boolean {
        return try {
            System.loadLibrary("llama")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 获取模型存储信息
     */
    fun getStorageInfo(): StorageInfo {
        val models = getLocalModels()
        val totalSize = models.sumOf { it.size }
        val freeSpace = File(context.filesDir, MODELS_DIR).freeSpace
        return StorageInfo(
            modelCount = models.size,
            totalSize = totalSize,
            freeSpace = freeSpace
        )
    }

    data class ModelInfo(
        val name: String,
        val path: String,
        val size: Long,
        val isLoaded: Boolean = false
    ) {
        val sizeFormatted: String
            get() = when {
                size > 1024 * 1024 * 1024 -> "%.1fGB".format(size.toDouble() / (1024 * 1024 * 1024))
                size > 1024 * 1024 -> "%.0fMB".format(size.toDouble() / (1024 * 1024))
                size > 1024 -> "%.0fKB".format(size.toDouble() / 1024)
                else -> "$size B"
            }
    }

    data class ModelSource(
        val name: String,
        val url: String,
        val size: String,
        val description: String
    )

    data class StorageInfo(
        val modelCount: Int,
        val totalSize: Long,
        val freeSpace: Long
    )
}