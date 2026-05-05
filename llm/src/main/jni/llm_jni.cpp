// JNI bridge for the small instruction-tuned model runtime.
//
// Mirrors `:whisper/src/main/jni/jni.c` but in C++ — llama.cpp's public API is
// `extern "C"` but the private headers we transitively pull through llama.h
// require C++.
//
// Threading model: every JNI entry point is invoked from a Kotlin coroutine on
// Dispatchers.Default (see LlmContext). Concurrency is enforced by a Kotlin
// Mutex on the same context handle, so the native side is single-threaded per
// context and does no extra locking.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>

#include "llama.h"

#define TAG "LlmJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// llama_backend_init must be called exactly once per process. We gate it
// behind a static flag so multiple LlmContext lifecycles in the same process
// don't double-init.
bool g_backend_inited = false;

struct LlmContextImpl {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
};

int min_int(int a, int b) { return a < b ? a : b; }
int max_int(int a, int b) { return a > b ? a : b; }

void ensure_backend() {
    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_whisperboard_llm_LlmLib_initContextFromFile(
        JNIEnv * env, jobject /*thiz*/, jstring jmodel_path, jint context_length) {
    ensure_backend();

    const char * model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    LOGI("Loading SLM from: %s (n_ctx=%d)", model_path, (int) context_length);

    llama_model_params mparams = llama_model_default_params();
    // n_gpu_layers = 0 — Android CPU build, no Vulkan/OpenCL. ggml's CPU
    // backend handles SIMD/dotprod/fp16 paths from CMake.
    mparams.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return 0;
    }

    const int trained_ctx = llama_model_n_ctx_train(model);
    const int n_ctx = (context_length > 0)
        ? min_int((int) context_length, trained_ctx)
        : trained_ctx;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx;
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;
    const int n_threads     = max_int(2, min_int(4, (int) sysconf(_SC_NPROCESSORS_ONLN)));
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0;
    }

    auto * impl = new LlmContextImpl{ model, ctx };
    return reinterpret_cast<jlong>(impl);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whisperboard_llm_LlmLib_freeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong context_ptr) {
    auto * impl = reinterpret_cast<LlmContextImpl *>(context_ptr);
    if (impl == nullptr) return;
    if (impl->ctx)   llama_free(impl->ctx);
    if (impl->model) llama_model_free(impl->model);
    delete impl;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whisperboard_llm_LlmLib_generate(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong context_ptr,
        jstring jsystem_prompt,
        jstring juser_prompt,
        jint max_tokens) {
    auto * impl = reinterpret_cast<LlmContextImpl *>(context_ptr);
    if (impl == nullptr || impl->ctx == nullptr || impl->model == nullptr) {
        LOGE("generate: invalid context");
        return env->NewStringUTF("");
    }

    const char * sys_chars  = env->GetStringUTFChars(jsystem_prompt, nullptr);
    const char * user_chars = env->GetStringUTFChars(juser_prompt, nullptr);

    // --- Step 1: render the chat template into a single prompt string -----
    //
    // llama_chat_apply_template uses the model's built-in chat template (when
    // tmpl == nullptr it picks the model's metadata template). add_ass=true
    // ends with the assistant turn opener so the next decoded token is the
    // start of the response.
    llama_chat_message msgs[] = {
        { "system", sys_chars  },
        { "user",   user_chars },
    };
    const size_t n_msgs = sizeof(msgs) / sizeof(msgs[0]);

    // First call probes the buffer size needed.
    int formatted_len = llama_chat_apply_template(
        nullptr, msgs, n_msgs, /*add_ass=*/true, nullptr, 0);
    std::string prompt;
    if (formatted_len > 0) {
        prompt.resize(formatted_len);
        formatted_len = llama_chat_apply_template(
            nullptr, msgs, n_msgs, /*add_ass=*/true,
            prompt.data(), (int) prompt.size());
        if (formatted_len > 0 && (size_t) formatted_len < prompt.size()) {
            prompt.resize(formatted_len);
        }
    }
    if (formatted_len <= 0 || prompt.empty()) {
        // Fallback: no built-in chat template metadata. Wrap manually with
        // ChatML-ish tags — most instruction-tuned models tolerate this and
        // it keeps the runtime functional even on unusual model files.
        prompt.clear();
        prompt += "<|system|>\n";
        prompt += sys_chars;
        prompt += "\n<|user|>\n";
        prompt += user_chars;
        prompt += "\n<|assistant|>\n";
    }

    env->ReleaseStringUTFChars(jsystem_prompt, sys_chars);
    env->ReleaseStringUTFChars(juser_prompt,   user_chars);

    // --- Step 2: tokenise -------------------------------------------------
    const llama_vocab * vocab = llama_model_get_vocab(impl->model);

    int needed = -llama_tokenize(
        vocab,
        prompt.c_str(), (int) prompt.size(),
        nullptr, 0,
        /*add_special=*/true,
        /*parse_special=*/true);
    if (needed <= 0) {
        LOGE("tokenize: probe returned %d", needed);
        return env->NewStringUTF("");
    }

    std::vector<llama_token> tokens(needed);
    int n_tokens = llama_tokenize(
        vocab,
        prompt.c_str(), (int) prompt.size(),
        tokens.data(), (int) tokens.size(),
        /*add_special=*/true,
        /*parse_special=*/true);
    if (n_tokens <= 0) {
        LOGE("tokenize: failed (%d)", n_tokens);
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    const int n_ctx = llama_n_ctx(impl->ctx);
    const int reserve_for_output = max_int(1, (int) max_tokens);
    if (n_tokens + reserve_for_output > n_ctx) {
        LOGW("prompt (%d tokens) + reserved output (%d) exceeds n_ctx (%d) — generation will be truncated",
             n_tokens, reserve_for_output, n_ctx);
        // Truncate the prompt from the front (keep the most recent context)
        // to leave room for output. This is a coarse safeguard; callers are
        // expected to keep transcripts comfortably under n_ctx.
        const int keep = max_int(1, n_ctx - reserve_for_output);
        tokens.erase(tokens.begin(), tokens.begin() + (n_tokens - keep));
        n_tokens = (int) tokens.size();
    }

    // --- Step 3: prefill the prompt ---------------------------------------
    llama_memory_t mem = llama_get_memory(impl->ctx);
    llama_memory_clear(mem, /*data=*/true);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(impl->ctx, batch) != 0) {
        LOGE("decode: prompt prefill failed");
        return env->NewStringUTF("");
    }

    // --- Step 4: greedy sampling loop -------------------------------------
    //
    // Greedy is the polish-stage default — temperature 0.2 on the API path
    // keeps polish near-deterministic; greedy here gets us the same
    // determinism on-device, with the simplest possible sampler chain so the
    // code remains comprehensible. A future slice can expose a
    // temperature/top-p picker.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    output.reserve((size_t) max_tokens * 4);

    int n_decoded = 0;
    llama_token next = 0;
    char piece_buf[256];

    while (n_decoded < (int) max_tokens) {
        next = llama_sampler_sample(sampler, impl->ctx, /*idx=*/-1);
        if (llama_vocab_is_eog(vocab, next)) {
            break;
        }

        const int n_chars = llama_token_to_piece(
            vocab, next, piece_buf, sizeof(piece_buf), /*lstrip=*/0,
            /*special=*/false);
        if (n_chars > 0) {
            output.append(piece_buf, n_chars);
        }

        // Feed the sampled token back as the next single-token batch.
        llama_batch step = llama_batch_get_one(&next, 1);
        if (llama_decode(impl->ctx, step) != 0) {
            LOGW("decode: step failed at token %d", n_decoded);
            break;
        }
        n_decoded++;
    }

    llama_sampler_free(sampler);

    if (output.empty()) {
        LOGW("generate: produced empty output (n_decoded=%d, max=%d)",
             n_decoded, (int) max_tokens);
    }

    return env->NewStringUTF(output.c_str());
}
