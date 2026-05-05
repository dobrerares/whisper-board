package com.whisperboard.llm

import android.util.Log

/**
 * Native bindings for the small instruction-tuned model runtime backed by
 * llama.cpp. Mirrors `WhisperLib` from `:whisper` — a thin pass-through to the
 * JNI shim. All decision logic and lifecycle management live in [LlmContext].
 *
 * Contract:
 * - All `external` methods are blocking and meant to be called from a worker
 *   dispatcher; [LlmContext] guards them with a mutex + IO/Default dispatchers.
 * - `contextPtr` is an opaque handle into native memory. Zero means "not
 *   initialised" / "freed" — the Kotlin layer enforces that invariant.
 */
class LlmLib {
    companion object {
        private const val TAG = "LlmLib"

        init {
            System.loadLibrary("llm_jni")
            Log.d(TAG, "Loaded llm_jni")
        }
    }

    /**
     * Load a GGUF model from an absolute filesystem path. Returns an opaque
     * handle, or 0L on failure.
     */
    external fun initContextFromFile(modelPath: String, contextLength: Int): Long

    /** Release native resources. Safe to call multiple times; no-op for 0L. */
    external fun freeContext(contextPtr: Long)

    /**
     * Run a single chat-completion. Both prompts feed
     * [llama_chat_apply_template](https://github.com/ggml-org/llama.cpp) before
     * tokenisation; greedy sampling produces deterministic polish output, which
     * matches the post-processor's `temperature = 0.2` shape on the API side.
     *
     * Returns the assistant's reply, or an empty string if generation fails or
     * the context cannot fit the prompt.
     */
    external fun generate(
        contextPtr: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
    ): String
}
