package com.ai.companion.core.llm

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"

        private var nativeLibChecked = false
        private var nativeLibAvailable = false

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
                name = "Llama-3.2-1B-Q4_K_M",
                url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-q4_k_m.gguf",
                size = "~700MB",
                description = "Meta Llama 3.2 1B，轻量高效"
            )
        )
    }

    private val modelsDir: File get() = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }

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

    fun scanDownloadDir(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
     * 通过 Android SAF 文件选择器的 Content URI 导入模型
     */
    suspend fun importModelFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileNameFromUri(uri) ?: "model_${System.currentTimeMillis()}.gguf"
            val destFile = File(modelsDir, fileName)
            if (destFile.exists()) {
                Log.w(TAG, "Model already exists: $fileName")
                return@withContext true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Could not open input stream for URI: $uri")
                return@withContext false
            }
            Log.d(TAG, "Imported model from URI: $fileName (${destFile.length() / 1024 / 1024}MB)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import model from URI", e)
            false
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

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

    fun getCurrentModelName(): String {
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        return prefs.getString("current_model", "") ?: ""
    }

    fun setCurrentModel(fileName: String) {
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_model", fileName).apply()
        Log.d(TAG, "Current model set to: $fileName")
    }

    /**
     * 检查原生库是否可用，结果缓存避免重复加载
     */
    fun isNativeLibAvailable(): Boolean {
        if (!nativeLibChecked) {
            nativeLibAvailable = try {
                System.loadLibrary("llama")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
            nativeLibChecked = true
        }
        return nativeLibAvailable
    }

    fun getStorageInfo(): StorageInfo {
        val models = getLocalModels()
        val totalSize = models.sumOf { it.size }
        val freeSpace = modelsDir.freeSpace
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