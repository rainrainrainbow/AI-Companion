# JNI bridge for llama.cpp
#include <jni.h>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    bool training_mode = false;
    float training_progress = 0.0f;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeInit(
    JNIEnv *env, jobject /*thiz*/, jstring model_path, jint n_ctx) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Initializing llama with model: %s", path);

    auto *ctx = new LlamaContext();

    // 初始化后端
    llama_backend_init();

    // 模型参数
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 99; // 尽可能使用GPU

    ctx->model = llama_load_model_from_file(path, model_params);
    if (!ctx->model) {
        LOGE("Failed to load model");
        delete ctx;
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    // 上下文参数
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    ctx->ctx = llama_new_context_with_model(ctx->model, ctx_params);
    if (!ctx->ctx) {
        LOGE("Failed to create context");
        llama_free_model(ctx->model);
        delete ctx;
        env->ReleaseStringUTFChars(model_path, path);
        return 0;
    }

    // 采样器
    ctx->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(ctx->smpl, llama_sampler_init_greedy());
    llama_sampler_chain_add(ctx->smpl, llama_sampler_init_softmax(1.0f));

    env->ReleaseStringUTFChars(model_path, path);
    LOGI("Model loaded successfully, handle: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGenerate(
    JNIEnv *env, jobject /*thiz*/, jlong handle,
    jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jfloat repeat_penalty) {

    auto *ctx = reinterpret_cast<LlamaContext *>(handle);
    if (!ctx || !ctx->ctx) {
        return env->NewStringUTF("");
    }

    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating response for prompt length: %zu", strlen(prompt_str));

    // Tokenize
    auto *tokens = llama_tokenize(ctx->ctx, prompt_str, strlen(prompt_str), true, false);
    if (!tokens) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("");
    }

    // 采样器配置
    delete ctx->smpl;
    ctx->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(ctx->smpl, llama_sampler_init_softmax(temperature));
    llama_sampler_chain_add(ctx->smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(ctx->smpl, llama_sampler_init_repetition_penalty(repeat_penalty, 64));

    std::string response;
    int n_len = 0;

    for (int i = 0; i < tokens->size; i++) {
        llama_decode(ctx->ctx, llama_batch_get_one(tokens->data[i], 1, n_len, 0));
    }

    // 生成
    while (n_len < max_tokens) {
        llama_token new_token = llama_sampler_sample(ctx->smpl, ctx->ctx, -1);
        if (llama_token_is_eog(ctx->model, new_token)) break;

        char buf[256];
        int n = llama_token_to_piece(ctx->model, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);
        }

        llama_decode(ctx->ctx, llama_batch_get_one(&new_token, 1, tokens->size + n_len, 0));
        n_len++;
    }

    llama_tokens_free(tokens);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("Generated %zu chars", response.size());
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeLoadLoRA(
    JNIEnv *env, jobject /*thiz*/, jlong handle,
    jstring lora_path, jfloat scale) {

    auto *ctx = reinterpret_cast<LlamaContext *>(handle);
    if (!ctx || !ctx->model) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(lora_path, nullptr);
    bool success = llama_model_load_lora(ctx->model, path, scale, nullptr, 0);
    env->ReleaseStringUTFChars(lora_path, path);

    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeRelease(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *ctx = reinterpret_cast<LlamaContext *>(handle);
    if (ctx) {
        if (ctx->smpl) llama_sampler_free(ctx->smpl);
        if (ctx->ctx) llama_free(ctx->ctx);
        if (ctx->model) llama_free_model(ctx->model);
        llama_backend_free();
        delete ctx;
        LOGI("Model released");
    }
}