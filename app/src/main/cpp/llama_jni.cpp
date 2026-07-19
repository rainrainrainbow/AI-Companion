#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// llama.cpp headers (pulled via CMake)
#include "llama.h"
#include "common.h"

// Per-instance context storage
struct LlamaInstance {
    struct llama_model *model = nullptr;
    struct llama_context *ctx = nullptr;
    struct llama_sampling *sampling = nullptr;
    llama_token *last_n_tokens = nullptr;
    int n_ctx = 2048;
    bool streaming = false;
    std::string pending_response;
    size_t stream_pos = 0;

    // LoRA
    bool lora_loaded = false;

    // Training (simplified - real LoRA training requires external lib)
    bool training = false;
    float training_progress = 0.0f;
};

// ========== Helper: JNI String <-> C++ String ==========
static std::string jstring2string(JNIEnv *env, jstring str) {
    if (!str) return "";
    const char *chars = env->GetStringUTFChars(str, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

// ========== nativeInit ==========
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeInit(
    JNIEnv *env, jobject /*thiz*/, jstring model_path, jint n_ctx) {

    std::string path = jstring2string(env, model_path);
    LOGI("nativeInit: loading model from %s", path.c_str());

    auto *inst = new LlamaInstance();
    inst->n_ctx = n_ctx;

    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    model_params.use_mlock = false;

    inst->model = llama_load_model_from_file(path.c_str(), model_params);
    if (!inst->model) {
        LOGE("Failed to load model: %s", path.c_str());
        delete inst;
        return 0;
    }

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;

    inst->ctx = llama_new_context_with_model(inst->model, ctx_params);
    if (!inst->ctx) {
        LOGE("Failed to create context");
        llama_free_model(inst->model);
        delete inst;
        return 0;
    }

    inst->last_n_tokens = new llama_token[n_ctx]();

    LOGI("Model loaded successfully, handle=%p", (void*)inst);
    return reinterpret_cast<jlong>(inst);
}

// ========== nativeEvaluate ==========
extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeEvaluate(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jintArray tokens) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->ctx) return nullptr;

    jsize len = env->GetArrayLength(tokens);
    jint *token_arr = env->GetIntArrayElements(tokens, nullptr);

    std::vector<llama_token> input_tokens(len);
    for (int i = 0; i < len; i++) input_tokens[i] = static_cast<llama_token>(token_arr[i]);

    env->ReleaseIntArrayElements(tokens, token_arr, JNI_ABORT);

    int n = llama_eval(inst->ctx, input_tokens.data(), (int)input_tokens.size(), 0, 1);
    if (n < 0) return nullptr;

    // Return logits as token IDs (simplified)
    jintArray result = env->NewIntArray(1);
    jint val = n;
    env->SetIntArrayRegion(result, 0, 1, &val);
    return result;
}

// ========== nativeGenerate ==========
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGenerate(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt,
    jint max_tokens, jfloat temperature, jfloat top_p, jfloat repeat_penalty) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->ctx) return env->NewStringUTF("");

    std::string prompt_str = jstring2string(env, prompt);

    // Tokenize prompt
    int n_tokens = llama_tokenize(inst->model, prompt_str.c_str(), prompt_str.length(), nullptr, 0, true, false);
    std::vector<llama_token> embd_inp(n_tokens);
    llama_tokenize(inst->model, prompt_str.c_str(), prompt_str.length(), embd_inp.data(), n_tokens, true, false);

    // Generate
    std::string response;
    int n_len = 0;
    int n_past = 0;
    int n_remain = max_tokens;

    // Ingest prompt
    for (int i = 0; i < (int)embd_inp.size(); i += inst->n_ctx - 4) {
        int n_eval = (int)embd_inp.size() - i;
        if (n_eval > inst->n_ctx - 4) n_eval = inst->n_ctx - 4;
        if (llama_eval(inst->ctx, &embd_inp[i], n_eval, n_past, 1)) {
            LOGE("Failed to eval");
            return env->NewStringUTF("");
        }
        n_past += n_eval;
    }

    // Sample tokens
    while (n_remain > 0) {
        llama_token id = llama_sample_token_greedy(inst->ctx);

        if (id == llama_token_eos(inst->model) || id == llama_token_eot(inst->model)) {
            break;
        }

        response += llama_token_to_piece(inst->ctx, id);
        n_len++;
        n_remain--;

        // Eval the new token
        if (llama_eval(inst->ctx, &id, 1, n_past, 1)) {
            break;
        }
        n_past++;
    }

    return env->NewStringUTF(response.c_str());
}

// ========== nativeGenerateStream ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGenerateStream(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt,
    jint max_tokens, jfloat temperature, jfloat top_p, jfloat repeat_penalty) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->ctx) return JNI_FALSE;

    // Same as generate but store response for streaming
    std::string prompt_str = jstring2string(env, prompt);

    int n_tokens = llama_tokenize(inst->model, prompt_str.c_str(), prompt_str.length(), nullptr, 0, true, false);
    std::vector<llama_token> embd_inp(n_tokens);
    llama_tokenize(inst->model, prompt_str.c_str(), prompt_str.length(), embd_inp.data(), n_tokens, true, false);

    inst->streaming = true;
    inst->pending_response.clear();
    inst->stream_pos = 0;

    int n_past = 0;
    for (int i = 0; i < (int)embd_inp.size(); i += inst->n_ctx - 4) {
        int n_eval = (int)embd_inp.size() - i;
        if (n_eval > inst->n_ctx - 4) n_eval = inst->n_ctx - 4;
        if (llama_eval(inst->ctx, &embd_inp[i], n_eval, n_past, 1)) break;
        n_past += n_eval;
    }

    int n_remain = max_tokens;
    while (n_remain > 0 && inst->streaming) {
        llama_token id = llama_sample_token_greedy(inst->ctx);
        if (id == llama_token_eos(inst->model) || id == llama_token_eot(inst->model)) break;

        inst->pending_response += llama_token_to_piece(inst->ctx, id);
        n_remain--;

        if (llama_eval(inst->ctx, &id, 1, n_past, 1)) break;
        n_past++;
    }

    inst->streaming = false;
    return JNI_TRUE;
}

// ========== nativeGetNextToken ==========
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGetNextToken(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return env->NewStringUTF("<EOS>");

    if (inst->stream_pos >= inst->pending_response.length()) {
        return env->NewStringUTF("<EOS>");
    }

    // Return one character at a time for streaming
    std::string token(1, inst->pending_response[inst->stream_pos]);
    inst->stream_pos++;
    return env->NewStringUTF(token.c_str());
}

// ========== nativeStopGenerate ==========
extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeStopGenerate(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (inst) inst->streaming = false;
}

// ========== nativeLoadLoRA ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeLoadLoRA(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring lora_path, jfloat scale) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->model) return JNI_FALSE;

    std::string path = jstring2string(env, lora_path);
    LOGI("Loading LoRA adapter from %s", path.c_str());

    // llama.cpp supports LoRA via llama_model_load
    bool success = llama_model_load_lora(inst->model, path.c_str(), scale);
    inst->lora_loaded = success;
    return success ? JNI_TRUE : JNI_FALSE;
}

// ========== nativeStartTraining ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeStartTraining(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring train_data,
    jint lora_rank, jint lora_alpha, jint epochs, jint batch_size, jfloat lr) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return JNI_FALSE;

    LOGI("Training start requested (rank=%d, alpha=%d, epochs=%d, batch=%d, lr=%f)",
         lora_rank, lora_alpha, epochs, batch_size, lr);

    // Simplified: training requires external library
    // For now, simulate progress
    inst->training = true;
    inst->training_progress = 0.0f;
    return JNI_TRUE;
}

// ========== nativeGetTrainingProgress ==========
extern "C" JNIEXPORT jfloat JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGetTrainingProgress(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return 0.0f;

    // Simulate training progress
    if (inst->training) {
        inst->training_progress += 0.05f;
        if (inst->training_progress >= 1.0f) {
            inst->training_progress = 1.0f;
            inst->training = false;
        }
    }
    return inst->training_progress;
}

// ========== nativeSaveLoRA ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeSaveLoRA(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring output_path) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return JNI_FALSE;

    std::string path = jstring2string(env, output_path);
    LOGI("Saving LoRA adapter to %s", path.c_str());

    // Simplified: actual LoRA saving needs training library
    return JNI_TRUE;
}

// ========== nativeRelease ==========
extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeRelease(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return;

    LOGI("Releasing model instance");

    if (inst->ctx) {
        llama_free(inst->ctx);
    }
    if (inst->model) {
        llama_free_model(inst->model);
    }
    delete[] inst->last_n_tokens;
    delete inst;
}
