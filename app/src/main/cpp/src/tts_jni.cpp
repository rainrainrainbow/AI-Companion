# JNI bridge for VITS TTS
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#define TAG "VitsJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct VitsContext {
    void *model = nullptr;
    int sample_rate = 22050;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_companion_core_audio_TTSEngine_vitsInit(
    JNIEnv *env, jobject /*thiz*/, jstring model_path, jstring config_path) {

    const char *model = env->GetStringUTFChars(model_path, nullptr);
    const char *config = env->GetStringUTFChars(config_path, nullptr);

    LOGI("Initializing VITS with model: %s, config: %s", model, config);

    auto *ctx = new VitsContext();
    // VITS model loading would go here
    // For now, stub implementation

    env->ReleaseStringUTFChars(model_path, model);
    env->ReleaseStringUTFChars(config_path, config);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_ai_companion_core_audio_TTSEngine_vitsSynthesize(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring text) {

    auto *ctx = reinterpret_cast<VitsContext *>(handle);
    if (!ctx) return nullptr;

    const char *input = env->GetStringUTFChars(text, nullptr);
    LOGI("Synthesizing: %s", input);

    // Stub: return a short sine wave as placeholder
    int sample_count = ctx->sample_rate * 2; // 2 seconds
    std::vector<jshort> audio(sample_count, 0);

    jshortArray result = env->NewShortArray(sample_count);
    env->SetShortArrayRegion(result, 0, sample_count, audio.data());

    env->ReleaseStringUTFChars(text, input);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_audio_TTSEngine_vitsRelease(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *ctx = reinterpret_cast<VitsContext *>(handle);
    if (ctx) {
        delete ctx;
        LOGI("VITS model released");
    }
}