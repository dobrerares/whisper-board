package com.whisperboard.postprocessing

import android.util.Log
import com.whisperboard.llm.LlmContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PostProcessor] backed by a local llama.cpp runtime via the `:llm` module.
 *
 * Lifecycle (per Slice 4 brief):
 *
 * - The active SLM is supplied as a path through [llmContextFactory]; the
 *   processor lazily creates an [LlmContext] inside each [polish] call and
 *   closes it before returning. Whisper and the LLM never coexist in memory
 *   so a 6 GB device can host both stages.
 * - Failures (model file missing, native init failure, generation timeout,
 *   etc.) raise [PostProcessingException]; the router catches that and falls
 *   back to the raw transcript silently.
 *
 * The implementation is a sibling of [ApiPostProcessor], not a subclass — the
 * brief explicitly forbids refactoring them into a shared base. They share the
 * [PromptBuilder] so the system prompt is identical across runtimes; only the
 * transport changes.
 */
open class LocalPostProcessor(
    private val llmContextFactory: suspend () -> LlmContext,
    private val maxTokens: Int = LlmContext.DEFAULT_MAX_TOKENS,
) : PostProcessor {

    companion object {
        private const val TAG = "LocalPostProcessor"
    }

    override suspend fun polish(
        rawTranscript: String,
        context: PostProcessingContext,
    ): String = withContext(Dispatchers.Default) {
        if (rawTranscript.isBlank()) return@withContext rawTranscript

        val prompts = PromptBuilder.build(
            rawTranscript = rawTranscript,
            languageProfile = context.languageProfile,
            tier = PostProcessingTier.TIER_2,
        )

        val llm = try {
            llmContextFactory()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create LlmContext", e)
            throw PostProcessingException("Local SLM load failed: ${e.message}", e)
        }

        try {
            val output = try {
                llm.generate(
                    systemPrompt = prompts.system,
                    userPrompt = prompts.user,
                    maxTokens = maxTokens,
                )
            } catch (e: LlmContext.GenerationException) {
                throw PostProcessingException("Local SLM generation failed: ${e.message}", e)
            } catch (e: OutOfMemoryError) {
                // Native code can blow past the JVM heap — surface as a polite
                // fallback rather than letting the JVM die. The router will
                // commit raw text, which is the correct safety net.
                throw PostProcessingException("Local SLM out of memory", e)
            } catch (e: Exception) {
                throw PostProcessingException("Local SLM error: ${e.message}", e)
            }
            if (output.isBlank()) {
                throw PostProcessingException("Local SLM returned empty text")
            }
            output.trim()
        } finally {
            // Always unload — even on success — so the model doesn't pin RAM
            // alongside Whisper between dictations. This is the RAM-budget
            // contract the brief calls out.
            try {
                llm.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close LlmContext cleanly", e)
            }
        }
    }
}
