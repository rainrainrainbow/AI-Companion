package com.ai.companion.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语音合成引擎 - 使用Android TTS + 本地VITS模型备用
 */
class TTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "TTSEngine"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // JNI - VITS TTS (备用)
    private external fun vitsInit(modelPath: String, configPath: String): Long
    private external fun vitsSynthesize(handle: Long, text: String): ShortArray
    private external fun vitsRelease(handle: Long)

    private var vitsHandle: Long = 0

    init {
        try {
            System.loadLibrary("vits_tts")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "VITS native library not available")
        }
    }

    fun init() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Chinese TTS not supported")
                }
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(0.95f)
                isInitialized = true
                Log.d(TAG, "TTS initialized")
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized")
            synthesizeWithVITS(text)
            return
        }

        _isSpeaking.value = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }
        })

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utterance_${System.currentTimeMillis()}")
    }

    fun speakWithEmotion(text: String, emotion: String) {
        // 根据情绪调整语速/音调
        val (pitch, rate) = when (emotion) {
            "HAPPY" -> 1.1f to 1.05f
            "SAD" -> 0.9f to 0.85f
            "ANGRY" -> 0.95f to 1.1f
            "LOVING" -> 1.0f to 0.9f
            else -> 1.0f to 0.95f
        }
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        speak(text)
    }

    private fun synthesizeWithVITS(text: String) {
        if (vitsHandle == 0L) {
            Log.w(TAG, "VITS not available")
            return
        }
        try {
            val audioData = vitsSynthesize(vitsHandle, text)
            playAudioData(audioData)
        } catch (e: Exception) {
            Log.e(TAG, "VITS synthesis failed", e)
        }
    }

    private fun playAudioData(audioData: ShortArray) {
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(22050)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(audioData.size * 2)
            .build()

        audioTrack.play()
        audioTrack.write(audioData, 0, audioData.size)
        audioTrack.stop()
        audioTrack.release()
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun loadVITSModel(modelPath: String, configPath: String) {
        try {
            vitsHandle = vitsInit(modelPath, configPath)
            Log.d(TAG, "VITS model loaded: $vitsHandle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VITS model", e)
        }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (vitsHandle != 0L) {
            vitsRelease(vitsHandle)
            vitsHandle = 0
        }
    }
}