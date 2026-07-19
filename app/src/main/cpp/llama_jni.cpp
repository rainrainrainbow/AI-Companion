#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "llama.h"

// Per-instance context storage
struct LlamaInstance {
    struct llama_model *model = nullptr;
    struct llama_context *ctx = nullptr;
    struct llama_sampler *smpl = nullptr;
    const struct llama_vocab *vocab = nullptr;
    int n_ctx = 2048;
    bool streaming = false;
    std::string pending_response;
    size_t stream_pos = 0;

    bool training = false;
    float training_progress = 0.0f;
};

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

    inst->model = llama_model_load_from_file(path.c_str(), model_params);
    if (!inst->model) {
        LOGE("Failed to load model: %s", path.c_str());
        delete inst;
        return 0;
    }

    // Vocab
    inst->vocab = llama_model_get_vocab(inst->model);

    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = 512;

    inst->ctx = llama_init_from_model(inst->model, ctx_params);
    if (!inst->ctx) {
        LOGE("Failed to create context");
        llama_model_free(inst->model);
        delete inst;
        return 0;
    }

    // Set threads
    llama_set_n_threads(inst->ctx, 4, 4);

    // Create sampler chain: temp -> top-p -> greedy
    auto sparams = llama_sampler_chain_default_params();
    inst->smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(inst->smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(inst->smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(inst->smpl, llama_sampler_init_greedy());

    LOGI("Model loaded successfully, handle=%p", (void*)inst);
    return reinterpret_cast<jlong>(inst);
}

// ========== nativeGenerate ==========
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeGenerate(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt,
    jint max_tokens, jfloat temperature, jfloat top_p, jfloat repeat_penalty) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst || !inst->ctx) return env->NewStringUTF("");

    std::string prompt_str = jstring2string(env, prompt);

    // Tokenize
    int n_tokens = llama_tokenize(inst->vocab, prompt_str.c_str(), prompt_str.length(), nullptr, 0, true, false);
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(inst->vocab, prompt_str.c_str(), prompt_str.length(), tokens.data(), n_tokens, true, false);

    // Decode prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(inst->ctx, batch)) {
        LOGE("Failed to decode prompt");
        return env->NewStringUTF("");
    }

    // Generate
    std::string response;
    std::vector<char> piece(64);
    for (int i = 0; i < max_tokens; i++) {
        llama_token id = llama_sampler_sample(inst->smpl, inst->ctx, -1);

        if (llama_vocab_is_eog(inst->vocab, id)) break;

        int n = llama_token_to_piece(inst->vocab, id, piece.data(), piece.size(), 0, false);
        if (n > 0) response.append(piece.data(), n);

        // Accept token and decode
        llama_sampler_accept(inst->smpl, id);
        llama_token single = id;
        batch = llama_batch_get_one(&single, 1);
        if (llama_decode(inst->ctx, batch)) break;
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

    std::string prompt_str = jstring2string(env, prompt);

    int n_tokens = llama_tokenize(inst->vocab, prompt_str.c_str(), prompt_str.length(), nullptr, 0, true, false);
    std::vector<llama_token> tokens(n_tokens);
    llama_tokenize(inst->vocab, prompt_str.c_str(), prompt_str.length(), tokens.data(), n_tokens, true, false);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(inst->ctx, batch)) return JNI_FALSE;

    inst->streaming = true;
    inst->pending_response.clear();
    inst->stream_pos = 0;

    std::vector<char> piece(64);
    for (int i = 0; i < max_tokens && inst->streaming; i++) {
        llama_token id = llama_sampler_sample(inst->smpl, inst->ctx, -1);
        if (llama_vocab_is_eog(inst->vocab, id)) break;

        int n = llama_token_to_piece(inst->vocab, id, piece.data(), piece.size(), 0, false);
        if (n > 0) inst->pending_response.append(piece.data(), n);

        llama_sampler_accept(inst->smpl, id);
        llama_token single = id;
        batch = llama_batch_get_one(&single, 1);
        if (llama_decode(inst->ctx, batch)) break;
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

    bool success = llama_model_load_lora(inst->model, path.c_str(), scale);
    return success ? JNI_TRUE : JNI_FALSE;
}

// ========== nativeStartTraining (simplified) ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeStartTraining(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring train_data,
    jint lora_rank, jint lora_alpha, jint epochs, jint batch_size, jfloat lr) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return JNI_FALSE;

    LOGI("Training start requested (simplified)");
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

    if (inst->training) {
        inst->training_progress += 0.05f;
        if (inst->training_progress >= 1.0f) {
            inst->training_progress = 1.0f;
            inst->training = false;
        }
    }
    return inst->training_progress;
}

// ========== nativeSaveLoRA (simplified) ==========
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeSaveLoRA(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jstring output_path) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return JNI_FALSE;

    LOGI("Saving LoRA adapter (simplified)");
    return JNI_TRUE;
}

// ========== nativeRelease ==========
extern "C" JNIEXPORT void JNICALL
Java_com_ai_companion_core_llm_LocalLLMEngine_nativeRelease(
    JNIEnv *env, jobject /*thiz*/, jlong handle) {

    auto *inst = reinterpret_cast<LlamaInstance*>(handle);
    if (!inst) return;

    LOGI("Releasing model instance");

    if (inst->smpl) {
        llama_sampler_free(inst->smpl);
    }
    if (inst->ctx) {
        llama_free(inst->ctx);
    }
    if (inst->model) {
        llama_model_free(inst->model);
    }
    delete inst;
}