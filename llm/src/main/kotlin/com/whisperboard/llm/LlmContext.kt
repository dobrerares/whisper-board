package com.whisperboard.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Kotlin facade around a llama.cpp context. Mirrors `WhisperContext` from the
 * sibling `:whisper` module — same lifecycle (`createContext` -> `generate` ->
 * `close`), same mutex-guarded native handle, same reasoning about restricted
 * coroutine scopes on `close()`.
 *
 * RAM-budget choreography (per Slice 4 brief): the LLM is created lazily by
 * the caller right before each polish call and closed immediately afterwards,
 * so a 1-3B-parameter model never coexists in memory with the loaded Whisper
 * model. `LocalPostProcessor` owns this load/unload cycle.
 */
class LlmContext private constructor(
    private val modelPath: String,
    private val contextLength: Int,
) : Closeable {

    private val lib = LlmLib()
    private var contextPtr: Long = 0L
    private val mutex = Mutex()

    private fun initContext() {
        contextPtr = lib.initContextFromFile(modelPath, contextLength)
        require(contextPtr != 0L) { "Failed to initialize llama context from: $modelPath" }
    }

    /**
     * Run a single system+user chat completion. Returns the assistant message.
     * On generation failure the native side returns an empty string; we
     * surface that as [GenerationException] so the post-processor can fall
     * back to the raw transcript.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): String {
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                check(contextPtr != 0L) { "Llm context is not initialized or has been closed" }
                val output = lib.generate(contextPtr, systemPrompt, userPrompt, maxTokens)
                if (output.isBlank()) {
                    throw GenerationException("Empty generation from llama.cpp")
                }
                output.trim()
            }
        }
    }

    override fun close() {
        // Mirrors WhisperContext.close — runBlocking on a Mutex around the
        // native free is safe because close is a one-shot terminal operation.
        runBlocking {
            mutex.withLock {
                if (contextPtr != 0L) {
                    lib.freeContext(contextPtr)
                    contextPtr = 0L
                }
            }
        }
    }

    class GenerationException(message: String) : Exception(message)

    companion object {
        /**
         * Default cap on assistant tokens. Tier 2 polish output is bounded by
         * the input transcript length; 512 tokens is roomy enough for ~3-4
         * paragraphs of polished text without letting the model run away.
         */
        const val DEFAULT_MAX_TOKENS = 512

        /**
         * Default context window. 4096 fits a generous transcript + system
         * prompt for instruction-tuned 1-3B models, which typically train at
         * 4k or 8k. The native side clips to the model's trained context if
         * this exceeds it.
         */
        const val DEFAULT_CONTEXT_LENGTH = 4096

        suspend fun createContext(
            modelPath: String,
            contextLength: Int = DEFAULT_CONTEXT_LENGTH,
        ): LlmContext {
            return withContext(Dispatchers.IO) {
                LlmContext(modelPath, contextLength).also { it.initContext() }
            }
        }
    }
}
