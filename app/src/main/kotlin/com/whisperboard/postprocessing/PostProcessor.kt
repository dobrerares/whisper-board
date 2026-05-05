package com.whisperboard.postprocessing

/**
 * A pipeline stage that runs after transcription. Takes raw Whisper output and
 * produces cleaned, formatted text. Mirrors the shape of `TranscriptionEngine`
 * — sealed interface, pluggable implementations, strategy-routed by
 * `PostProcessingRouter`.
 *
 * Implementations:
 * - [ApiPostProcessor] — OpenAI-compatible `/v1/chat/completions` runtime.
 * - `LocalPostProcessor` — local SLM runtime, lands in slice #4.
 */
sealed interface PostProcessor {
    /**
     * Polish a raw transcript. Returns the cleaned, formatted text.
     *
     * Throws [PostProcessingException] on any failure (timeout, network,
     * malformed response). Callers — typically [PostProcessingRouter] — are
     * expected to fall back to the raw transcript silently.
     */
    suspend fun polish(rawTranscript: String, context: PostProcessingContext): String

    fun close() {}
}

/**
 * Inputs to a [PostProcessor] beyond the raw transcript itself. Carries the
 * language profile (consumed by [PromptBuilder] as system-prompt context) and
 * any target-field hints.
 *
 * The `languageProfile` is wired through but consumed as an empty profile in
 * Slice 1 — the multilingual prompt context lands in slice #5.
 */
data class PostProcessingContext(
    val languageProfile: Set<String> = emptySet(),
    val targetFieldHint: String? = null,
)

class PostProcessingException(message: String, cause: Throwable? = null) : Exception(message, cause)
