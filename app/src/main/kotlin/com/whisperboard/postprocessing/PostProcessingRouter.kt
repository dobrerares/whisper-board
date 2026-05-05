package com.whisperboard.postprocessing

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of a single post-processing call. The router never throws — failures
 * are surfaced as [Outcome.Fallback] so the caller can both commit raw text
 * and surface a small "polish unavailable" indicator.
 */
sealed interface PostProcessingOutcome {
    /** Polish ran and produced text. */
    data class Polished(val text: String) : PostProcessingOutcome

    /** Polish was disabled or routed to OFF — raw text is returned unchanged. */
    data class Skipped(val text: String) : PostProcessingOutcome

    /**
     * Polish was attempted but failed; raw text is returned and [reason]
     * carries a short human-readable description for telemetry / surface.
     */
    data class Fallback(val text: String, val reason: String) : PostProcessingOutcome
}

/**
 * Strategy-dispatched router for the post-processing stage. Mirrors
 * `EngineRouter` — same vocabulary, same routing logic.
 *
 * Slice 4 (this slice) lights up [PostProcessingStrategy.LOCAL_ONLY],
 * [PostProcessingStrategy.LOCAL_PREFERRED], and
 * [PostProcessingStrategy.API_WHEN_ONLINE] now that the local SLM runtime
 * exists in the `:llm` module:
 *
 * - `LOCAL_ONLY` runs `localPostProcessor` exclusively; falls back to raw on
 *   any failure.
 * - `LOCAL_PREFERRED` tries local with a configurable timeout, then falls
 *   over to the API processor if local times out or fails.
 * - `API_WHEN_ONLINE` runs the API path when [isOnline] returns true and an
 *   API processor is configured; otherwise falls back to local. Falls through
 *   to raw if neither is available.
 *
 * The router never throws on polish failure — instead it returns
 * [PostProcessingOutcome.Fallback] so callers can both commit the raw words
 * and surface a small unavailable indicator. Words are never lost.
 */
class PostProcessingRouter(
    private val polishModeProvider: suspend () -> Boolean,
    private val strategyProvider: suspend () -> PostProcessingStrategy,
    private val localTimeoutMsProvider: suspend () -> Long = { DEFAULT_LOCAL_TIMEOUT_MS },
    private val onlineCheck: () -> Boolean = { false },
) {
    companion object {
        private const val TAG = "PostProcessingRouter"

        /**
         * Default timeout for `LOCAL_PREFERRED`. Generous enough that a 1-3B
         * SLM polishing a few seconds of dictation comfortably finishes; tight
         * enough that a stuck generation doesn't hold up auto-insert. Callers
         * can override via the provider.
         */
        const val DEFAULT_LOCAL_TIMEOUT_MS: Long = 20_000L
    }

    @Volatile var apiPostProcessor: ApiPostProcessor? = null
    @Volatile var localPostProcessor: LocalPostProcessor? = null

    /**
     * Run the post-processing stage. The contract:
     * - Never throws.
     * - Never returns empty text when given non-empty input.
     * - When polish mode is off, returns [PostProcessingOutcome.Skipped].
     * - When the strategy is unroutable (no processor configured for the
     *   chosen path), returns [PostProcessingOutcome.Fallback] with the raw
     *   transcript and a short reason.
     * - On polish failure, returns [PostProcessingOutcome.Fallback] with the
     *   raw transcript and a short reason.
     */
    suspend fun polish(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        if (rawTranscript.isBlank()) {
            return PostProcessingOutcome.Skipped(rawTranscript)
        }
        if (!polishModeProvider()) {
            return PostProcessingOutcome.Skipped(rawTranscript)
        }
        val strategy = strategyProvider()
        return when (strategy) {
            PostProcessingStrategy.OFF -> PostProcessingOutcome.Skipped(rawTranscript)
            PostProcessingStrategy.API_ONLY -> polishViaApi(rawTranscript, context)
            PostProcessingStrategy.LOCAL_ONLY -> polishViaLocal(rawTranscript, context)
            PostProcessingStrategy.LOCAL_PREFERRED ->
                polishViaLocalWithApiFallback(rawTranscript, context)
            PostProcessingStrategy.API_WHEN_ONLINE ->
                polishByConnectivity(rawTranscript, context)
        }
    }

    private suspend fun polishViaApi(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        val processor = apiPostProcessor
            ?: return PostProcessingOutcome.Fallback(rawTranscript, "API not configured")
        return runProcessor(processor, rawTranscript, context, label = "API")
    }

    private suspend fun polishViaLocal(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        val processor = localPostProcessor
            ?: return PostProcessingOutcome.Fallback(
                rawTranscript,
                "Local SLM not configured — pick a model in Settings",
            )
        return runProcessor(processor, rawTranscript, context, label = "Local")
    }

    private suspend fun polishViaLocalWithApiFallback(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        val local = localPostProcessor
        val timeoutMs = localTimeoutMsProvider()

        if (local != null) {
            val outcome = withTimeoutOrNull(timeoutMs) {
                runProcessor(local, rawTranscript, context, label = "Local")
            }
            when (outcome) {
                is PostProcessingOutcome.Polished -> return outcome
                is PostProcessingOutcome.Skipped -> return outcome
                is PostProcessingOutcome.Fallback -> {
                    Log.w(TAG, "Local polish failed (${outcome.reason}); falling back to API")
                    // fall through to API attempt
                }
                null -> {
                    Log.w(TAG, "Local polish timed out after ${timeoutMs}ms; falling back to API")
                }
            }
        }

        val api = apiPostProcessor
            ?: return PostProcessingOutcome.Fallback(
                rawTranscript,
                if (local == null) "No local SLM and API not configured"
                else "Local polish failed and API not configured",
            )
        return runProcessor(api, rawTranscript, context, label = "API (fallback)")
    }

    private suspend fun polishByConnectivity(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        val online = onlineCheck()
        return if (online && apiPostProcessor != null) {
            polishViaApi(rawTranscript, context)
        } else {
            polishViaLocal(rawTranscript, context)
        }
    }

    private suspend fun runProcessor(
        processor: PostProcessor,
        rawTranscript: String,
        context: PostProcessingContext,
        label: String,
    ): PostProcessingOutcome {
        return try {
            val polished = processor.polish(rawTranscript, context)
            PostProcessingOutcome.Polished(polished)
        } catch (e: PostProcessingException) {
            Log.w(TAG, "$label post-processing failed: ${e.message}")
            PostProcessingOutcome.Fallback(rawTranscript, e.message ?: "polish failed")
        } catch (e: Exception) {
            Log.w(TAG, "$label post-processing failed unexpectedly", e)
            PostProcessingOutcome.Fallback(rawTranscript, e.message ?: "polish failed")
        }
    }

    fun close() {
        apiPostProcessor = null
        localPostProcessor?.let {
            // PostProcessor.close is a default no-op for ApiPostProcessor; for
            // LocalPostProcessor it's also a no-op because the underlying
            // LlmContext is created and torn down per-call. Still call it for
            // consistency with the interface contract.
            try { it.close() } catch (_: Exception) {}
        }
        localPostProcessor = null
    }
}
