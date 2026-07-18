# JNI bridge for whisper.cpp
#include <jni.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_companion_core_audio_STTEngine_whisperInit(
    JNIEnv *env, jobject /*thiz*/, jstring model_path) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing whisper with model: %s", path);

    struct whisper_context *ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(model_path, path);

    if (!ctx) {
        LOGE("Failed to load whisper model");
        return 0;
    }

    LOGI("Whisper model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_companion_core_audio_STTEngine_whisperTranscribe(
    JNIEnv *env, jobject /*thiz*/, jlong handle,
    jshortArray audio_data, jint len) {

    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) {
        return env->NewStringUTF("");
    }

    jshort *audio = env->GetShortArrayElements(audio_data, nullptr);
    jsize audio_len = env->GetArrayLength(audio_data);

    // 转写参数
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language = "zh";
    params.n_threads = 4;
    params.offset_ms = 0;

    // 执行转写
    if (whisper_full(ctx, params, reinterpret_cast<float *>(audio), audio_len) != 0) {
        LOGE("Failed to transcribe");
        env->ReleaseShortArrayElements(audio_data, audio, JNI_ABORT);
        return env->NewStringUTF("");
    }

    // 获取结果
    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            result += text;
        }
    }

    env->ReleaseShortArrayElements(audio_data, audio, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_audio_STTEngine_whisperRelease(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Whisper model released");
    }
}