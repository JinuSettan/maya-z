#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include <cstdlib>

#include "llama.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * Real Native llama.cpp context holding actual pointers to llama_model and llama_context.
 */
struct LlamaRealNativeContext {
    std::string model_path;
    int n_threads;
    int n_ctx;
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" {

// 1. nativeInitModel
JNIEXPORT jlong JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeInitModel(
        JNIEnv *env, jobject instance, jstring model_path, jint n_threads, jint n_ctx) {
    if (!model_path) return 0;

    const char *path_cstr = env->GetStringUTFChars(model_path, nullptr);
    LOGI("nativeInitModel: Loading real GGUF model from path: %s (Threads: %d, Ctx: %d)", path_cstr, n_threads, n_ctx);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;

    llama_model* model = llama_load_model_from_file(path_cstr, mparams);
    env->ReleaseStringUTFChars(model_path, path_cstr);

    if (!model) {
        LOGE("nativeInitModel: Failed to load GGUF model from file!");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) {
        LOGE("nativeInitModel: Failed to create llama_context!");
        llama_free_model(model);
        return 0;
    }

    LlamaRealNativeContext* native_ctx = new LlamaRealNativeContext();
    native_ctx->model_path = path_cstr;
    native_ctx->n_threads = cparams.n_threads;
    native_ctx->n_ctx = cparams.n_ctx;
    native_ctx->model = model;
    native_ctx->ctx = ctx;

    LOGI("nativeInitModel: Successfully initialized real llama_model (%p) and llama_context (%p)", model, ctx);
    return reinterpret_cast<jlong>(native_ctx);
}

JNIEXPORT jlong JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeInitModel(
        JNIEnv *env, jobject instance, jstring model_path, jint n_threads, jint n_ctx) {
    return Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeInitModel(env, instance, model_path, n_threads, n_ctx);
}

// 2. nativeTokenize
JNIEXPORT jintArray JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeTokenize(
        JNIEnv *env, jobject instance, jlong ptr, jstring text, jboolean add_bos) {
    if (ptr == 0 || !text) return env->NewIntArray(0);

    LlamaRealNativeContext* native_ctx = reinterpret_cast<LlamaRealNativeContext*>(ptr);
    if (!native_ctx->model) return env->NewIntArray(0);

    const char *text_cstr = env->GetStringUTFChars(text, nullptr);
    std::string prompt_str(text_cstr);
    env->ReleaseStringUTFChars(text, text_cstr);

    int max_tokens = prompt_str.length() + 32;
    std::vector<llama_token> tokens(max_tokens);

    int n_tokens = llama_tokenize(
        native_ctx->model,
        prompt_str.c_str(),
        prompt_str.length(),
        tokens.data(),
        max_tokens,
        add_bos,
        true // parse special tokens
    );

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            native_ctx->model,
            prompt_str.c_str(),
            prompt_str.length(),
            tokens.data(),
            tokens.size(),
            add_bos,
            true
        );
    }

    if (n_tokens <= 0) return env->NewIntArray(0);

    jintArray result = env->NewIntArray(n_tokens);
    if (result) {
        env->SetIntArrayRegion(result, 0, n_tokens, reinterpret_cast<const jint*>(tokens.data()));
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeTokenize(
        JNIEnv *env, jobject instance, jlong ptr, jstring text, jboolean add_bos) {
    return Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeTokenize(env, instance, ptr, text, add_bos);
}

// 3. nativeEval
JNIEXPORT jboolean JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeEval(
        JNIEnv *env, jobject instance, jlong ptr, jintArray tokens) {
    if (ptr == 0 || !tokens) return JNI_FALSE;

    LlamaRealNativeContext* native_ctx = reinterpret_cast<LlamaRealNativeContext*>(ptr);
    if (!native_ctx->ctx || !native_ctx->model) return JNI_FALSE;

    jsize n_tokens = env->GetArrayLength(tokens);
    if (n_tokens == 0) return JNI_TRUE;

    jint* token_data = env->GetIntArrayElements(tokens, nullptr);

    llama_batch batch = llama_batch_get_one(reinterpret_cast<llama_token*>(token_data), n_tokens, 0, 0);
    int res = llama_decode(native_ctx->ctx, batch);

    env->ReleaseIntArrayElements(tokens, token_data, JNI_ABORT);
    return res == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeEval(
        JNIEnv *env, jobject instance, jlong ptr, jintArray tokens) {
    return Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeEval(env, instance, ptr, tokens);
}

// 4. nativeSampleToken
JNIEXPORT jint JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeSampleToken(
        JNIEnv *env, jobject instance, jlong ptr, jfloat temperature, jfloat top_p) {
    if (ptr == 0) return -1;

    LlamaRealNativeContext* native_ctx = reinterpret_cast<LlamaRealNativeContext*>(ptr);
    if (!native_ctx->ctx || !native_ctx->model) return -1;

    float* logits = llama_get_logits(native_ctx->ctx);
    int n_vocab = llama_n_vocab(native_ctx->model);

    std::vector<llama_token_data> candidates;
    candidates.reserve(n_vocab);
    for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
        candidates.push_back(llama_token_data{token_id, logits[token_id], 0.0f});
    }

    llama_token_data_array candidates_p = {
        candidates.data(),
        candidates.size(),
        false
    };

    if (temperature > 0.0f) {
        llama_sample_top_k(native_ctx->ctx, &candidates_p, 40, 1);
        llama_sample_top_p(native_ctx->ctx, &candidates_p, top_p, 1);
        llama_sample_temp(native_ctx->ctx, &candidates_p, temperature);
        return llama_sample_token(native_ctx->ctx, &candidates_p);
    } else {
        return llama_sample_token_greedy(native_ctx->ctx, &candidates_p);
    }
}

JNIEXPORT jint JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeSampleToken(
        JNIEnv *env, jobject instance, jlong ptr, jfloat temperature, jfloat top_p) {
    return Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeSampleToken(env, instance, ptr, temperature, top_p);
}

// 5. nativeTokenToPiece
JNIEXPORT jstring JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeTokenToPiece(
        JNIEnv *env, jobject instance, jlong ptr, jint token) {
    if (ptr == 0) return env->NewStringUTF("");

    LlamaRealNativeContext* native_ctx = reinterpret_cast<LlamaRealNativeContext*>(ptr);
    if (!native_ctx->model) return env->NewStringUTF("");

    char buf[128];
    int n = llama_token_to_piece(native_ctx->model, token, buf, sizeof(buf), 0, true);
    if (n < 0) return env->NewStringUTF("");

    buf[n] = '\0';
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeTokenToPiece(
        JNIEnv *env, jobject instance, jlong ptr, jint token) {
    return Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeTokenToPiece(env, instance, ptr, token);
}

// 6. nativeFreeModel
JNIEXPORT void JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeFreeModel(
        JNIEnv *env, jobject instance, jlong ptr) {
    if (ptr != 0) {
        LlamaRealNativeContext* native_ctx = reinterpret_cast<LlamaRealNativeContext*>(ptr);
        if (native_ctx->ctx) {
            llama_free(native_ctx->ctx);
            native_ctx->ctx = nullptr;
        }
        if (native_ctx->model) {
            llama_free_model(native_ctx->model);
            native_ctx->model = nullptr;
        }
        delete native_ctx;
        LOGI("nativeFreeModel: Successfully freed llama_context, llama_model, and native context memory.");
    }
}

JNIEXPORT void JNICALL
Java_com_cocateail_maya_data_native_LlamaBridge_nativeFreeModel(
        JNIEnv *env, jobject instance, jlong ptr) {
    Java_com_cocateail_maya_data_native_LlamaBridge_00024Companion_nativeFreeModel(env, instance, ptr);
}

}
