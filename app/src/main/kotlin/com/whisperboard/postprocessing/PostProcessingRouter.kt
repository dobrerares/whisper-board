package com.whisperboard.postprocessing

import android.util.Log

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
 * Slice 1 only routes [PostProcessingStrategy.OFF] and
 * [PostProcessingStrategy.API_ONLY] meaningfully. The `LOCAL_*` strategies
 * fall back to passing the raw transcript through (treated as `OFF`) until
 * the local runtime lands in slice #4.
 *
 * The router never throws on polish failure — instead it returns
 * [PostProcessingOutcome.Fallback] so callers can both commit the raw words
 * and surface a small unavailable indicator. Words are never lost.
 */
class PostProcessingRouter(
    private val polishModeProvider: suspend () -> Boolean,
    private val strategyProvider: suspend () -> PostProcessingStrategy,
) {
    companion object {
        private const val TAG = "PostProcessingRouter"
    }

    @Volatile var apiPostProcessor: ApiPostProcessor? = null

    /**
     * Run the post-processing stage. The contract:
     * - Never throws.
     * - Never returns empty text when given non-empty input.
     * - When polish mode is off, returns [PostProcessingOutcome.Skipped].
     * - When the strategy is unroutable in this slice, returns
     *   [PostProcessingOutcome.Skipped].
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
            // Slice #4 will route these. Until then, behave like OFF so
            // strategy persistence is forward-compatible.
            PostProcessingStrategy.LOCAL_ONLY,
            PostProcessingStrategy.LOCAL_PREFERRED,
            PostProcessingStrategy.API_WHEN_ONLINE -> {
                Log.d(TAG, "Strategy $strategy is not yet routable; falling through to raw")
                PostProcessingOutcome.Skipped(rawTranscript)
            }
        }
    }

    private suspend fun polishViaApi(
        rawTranscript: String,
        context: PostProcessingContext,
    ): PostProcessingOutcome {
        val processor = apiPostProcessor
            ?: return PostProcessingOutcome.Fallback(rawTranscript, "API not configured")
        return try {
            val polished = processor.polish(rawTranscript, context)
            PostProcessingOutcome.Polished(polished)
        } catch (e: PostProcessingException) {
            Log.w(TAG, "API post-processing failed: ${e.message}")
            PostProcessingOutcome.Fallback(rawTranscript, e.message ?: "polish failed")
        } catch (e: Exception) {
            Log.w(TAG, "API post-processing failed unexpectedly", e)
            PostProcessingOutcome.Fallback(rawTranscript, e.message ?: "polish failed")
        }
    }

    fun close() {
        apiPostProcessor = null
    }
}
