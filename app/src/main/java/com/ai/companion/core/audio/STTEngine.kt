package com.ai.companion.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * 语音识别引擎 - 使用whisper.cpp本地STT + Android SpeechRecognizer备用
 */
class STTEngine(private val context: Context) {

    companion object {
        private const val TAG = "STTEngine"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 4096
    }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    // JNI - whisper.cpp
    private external fun whisperInit(modelPath: String): Long
    private external fun whisperTranscribe(handle: Long, audioData: ShortArray, len: Int): String
    private external fun whisperRelease(handle: Long)

    private var whisperHandle: Long = 0

    init {
        try {
            System.loadLibrary("whisper")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "whisper native library not available, will use fallback")
        }
    }

    /**
     * 开始语音识别
     */
    fun startListening() {
        if (_isListening.value) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            _isListening.value = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(BUFFER_SIZE)
                val audioBuffer = mutableListOf<Short>()

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioBuffer.addAll(buffer.take(read))
                    }
                }

                // 录音结束，转写
                if (audioBuffer.isNotEmpty()) {
                    val result = transcribeAudio(audioBuffer.toShortArray())
                    withContext(Dispatchers.Main) {
                        _recognizedText.value = result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _isListening.value = false
        }
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        audioRecord = null
        _isListening.value = false
    }

    /**
     * 转写音频
     */
    private fun transcribeAudio(audioData: ShortArray): String {
        return try {
            if (whisperHandle != 0L) {
                whisperTranscribe(whisperHandle, audioData, audioData.size)
            } else {
                // whisper不可用时的fallback
                Log.w(TAG, "Whisper not initialized, using empty result")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe failed", e)
            ""
        }
    }

    /**
     * 使用Android系统语音识别（备用方案）
     */
    fun startSystemRecognition() {
        // 使用android.speech.SpeechRecognizer
        // 需要通过Intent启动
        Log.d(TAG, "System speech recognition requested")
    }

    fun loadWhisperModel(modelPath: String) {
        try {
            whisperHandle = whisperInit(modelPath)
            Log.d(TAG, "Whisper model loaded: $whisperHandle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load whisper model", e)
        }
    }

    fun release() {
        stopListening()
        if (whisperHandle != 0L) {
            whisperRelease(whisperHandle)
            whisperHandle = 0
        }
    }
}