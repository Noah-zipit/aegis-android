// Aegis offline AI — JNI bridge to llama.cpp (CPU only, arm64-v8a).
//
// Matches com.aegis.browser.ai.LlamaBridge. Written against llama.cpp tag b11496:
//   - llama_model_load_from_file / llama_model_free
//   - llama_init_from_model / llama_free
//   - llama_chat_apply_template (NULL tmpl = model's own template, "smollm2" supported)
//   - llama_tokenize / llama_token_to_piece / llama_vocab_eos
//   - llama_batch_get_one / llama_decode / llama_memory_clear(llama_get_memory(ctx), true)
//   - sampler chain: temp -> top_k -> top_p -> dist
//
// Streaming: nativeGenerateStream calls TokenCallback.onToken(token) from the
// calling thread once per emitted token.

#include <jni.h>

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <ctime>
#include <unistd.h>

#include <android/log.h>

#include "llama.h"

#define LOG_TAG "AegisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
const llama_vocab* g_vocab = nullptr;
std::atomic<bool> g_stop{false};
std::mutex g_mutex;

const char* kSystemPrompt =
    "You are Aegis AI, a helpful on-device assistant. Keep answers short and plain.";

void unload_locked() {
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_aegis_browser_ai_LlamaBridge_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                                jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    g_stop = false;

    const char* cpath = env->GetStringUTFChars(modelPath, nullptr);
    std::string path(cpath ? cpath : "");
    if (cpath) env->ReleaseStringUTFChars(modelPath, cpath);
    if (path.empty()) {
        LOGE("nativeInit: empty model path");
        return JNI_FALSE;
    }

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU only
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("nativeInit: model load failed: %s", path.c_str());
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    long procs = sysconf(_SC_NPROCESSORS_ONLN);
    int threads = (int)(procs > 0 ? procs : 4);
    if (threads < 1) threads = 1;
    if (threads > 8) threads = 8;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("nativeInit: context init failed");
        unload_locked();
        return JNI_FALSE;
    }

    LOGI("nativeInit: ready (%s, threads=%d)", path.c_str(), threads);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_aegis_browser_ai_LlamaBridge_nativeGenerateStream(JNIEnv* env, jobject /*thiz*/,
                                                           jstring prompt, jint maxTokens,
                                                           jobject callback) {
    if (!g_model || !g_ctx || !g_vocab) {
        LOGE("nativeGenerateStream: not initialized");
        return -1;
    }
    g_stop = false;

    const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
    std::string userText(cprompt ? cprompt : "");
    if (cprompt) env->ReleaseStringUTFChars(prompt, cprompt);

    // Global ref so the callback stays valid for the whole generation.
    jobject cbRef = env->NewGlobalRef(callback);
    jclass cbClass = env->GetObjectClass(cbRef);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGE("nativeGenerateStream: onToken method not found");
        env->DeleteGlobalRef(cbRef);
        return -1;
    }

    // --- Build the prompt with the model's own chat template ---
    llama_chat_message msgs[2] = {
        {"system", kSystemPrompt},
        {"user", userText.c_str()},
    };
    std::string formatted;
    {
        int32_t need = llama_chat_apply_template(nullptr, msgs, 2, true, nullptr, 0);
        if (need > 0) {
            std::vector<char> buf((size_t)need + 1);
            int32_t wrote =
                llama_chat_apply_template(nullptr, msgs, 2, true, buf.data(), (int32_t)buf.size());
            if (wrote > 0) formatted.assign(buf.data(), (size_t)wrote);
        }
    }
    if (formatted.empty()) {
        // Fallback: manual ChatML wrap (SmolLM2's template family).
        LOGI("nativeGenerateStream: template unavailable, using ChatML fallback");
        formatted = "<|im_start|>system\n" + std::string(kSystemPrompt) +
                    "<|im_end|>\n<|im_start|>user\n" + userText +
                    "<|im_end|>\n<|im_start|>assistant\n";
    }

    // --- Tokenize ---
    const int32_t n_tok_max = 2048;
    std::vector<llama_token> tokens((size_t)n_tok_max);
    int32_t n_tok = llama_tokenize(g_vocab, formatted.c_str(), (int32_t)formatted.size(),
                                   tokens.data(), n_tok_max, true, true);
    if (n_tok <= 0) {
        LOGE("nativeGenerateStream: tokenize failed");
        env->DeleteGlobalRef(cbRef);
        return -1;
    }
    tokens.resize((size_t)n_tok);

    // --- Sampler: temp -> top_k -> top_p -> dist ---
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t)time(nullptr)));

    // --- Prefill ---
    bool ok = true;
    for (llama_token t : tokens) {
        if (g_stop) {
            ok = false;
            break;
        }
        llama_batch batch = llama_batch_get_one(&t, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            ok = false;
            break;
        }
        llama_sampler_accept(smpl, t);
    }

    // --- Generate loop: sample -> callback -> decode ---
    const llama_token eos = llama_vocab_eos(g_vocab);
    int generated = 0;
    char piece[64];

    while (ok && generated < maxTokens && !g_stop) {
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        if (id == eos) break;

        int32_t n = llama_token_to_piece(g_vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) {
            std::string s(piece, (size_t)n);
            jstring js = env->NewStringUTF(s.c_str());
            if (js) {
                env->CallVoidMethod(cbRef, onToken, js);
                env->DeleteLocalRef(js);
            }
        }
        generated++;

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }

    llama_sampler_free(smpl);
    // Reset KV so the next turn starts clean (history is re-supplied in the prompt).
    llama_memory_clear(llama_get_memory(g_ctx), true);
    env->DeleteGlobalRef(cbRef);

    LOGI("nativeGenerateStream: done, %d tokens", generated);
    return (jint)generated;
}

JNIEXPORT void JNICALL
Java_com_aegis_browser_ai_LlamaBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_stop = true;
}

JNIEXPORT void JNICALL
Java_com_aegis_browser_ai_LlamaBridge_nativeUnload(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
    llama_backend_free();
    LOGI("nativeUnload: done");
}

}  // extern "C"
