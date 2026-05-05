package com.whisperboard.postprocessing

/**
 * The cleanup tier the prompt is built for. Tier 2 is the v1 default:
 * filler/false-start cleanup plus list and paragraph detection. Tier 3+ is
 * explicitly out of scope for v1.
 */
enum class PostProcessingTier {
    TIER_2,
}

/**
 * A pair of prompts ready to feed into an OpenAI-compatible chat-completions
 * request — `system` becomes the system message, `user` becomes the user
 * message.
 */
data class PromptPair(
    val system: String,
    val user: String,
)

/**
 * Pure builder for post-processor prompts. Given a raw transcript, a language
 * profile, and a tier, produces the system + user prompts.
 *
 * Tier 2 system prompt is the contract — its content is asserted on in
 * [com.whisperboard.postprocessing.PromptBuilderTest] so prompt drift is
 * caught.
 *
 * The `languageProfile` is wired through but consumed as a placeholder in
 * Slice 1 (empty / `[auto]`). Slice #5 expands this into actual code-switching
 * context.
 */
object PromptBuilder {

    fun build(
        rawTranscript: String,
        languageProfile: Set<String> = emptySet(),
        tier: PostProcessingTier = PostProcessingTier.TIER_2,
    ): PromptPair = when (tier) {
        PostProcessingTier.TIER_2 -> PromptPair(
            system = tier2SystemPrompt(languageProfile),
            user = rawTranscript,
        )
    }

    private fun tier2SystemPrompt(languageProfile: Set<String>): String {
        val profileLine = if (languageProfile.isEmpty()) {
            "The user's spoken languages are not declared; do not assume a language."
        } else {
            "The user speaks: ${languageProfile.sorted().joinToString(", ")}."
        }
        return """
            You are a transcript polisher. The user dictated text and a speech-to-text engine produced the transcript below. Your job is to clean it up and return ONLY the polished text — no commentary, no quoting, no preamble.

            Apply these passes in order:
            1. Remove filler words ("um", "uh", "like", "you know") and obvious false starts (repeated half-words, restarts after a stutter).
            2. Fix punctuation and capitalization. Capitalize sentence starts and proper nouns; add commas, periods, and question marks where the spoken cadence clearly implies them.
            3. Detect spoken lists ("first... second... third...", "one,... two,... three,...") and emit them as a Markdown bullet list.
            4. Detect paragraph boundaries from clear pauses or topic shifts and split long blocks into paragraphs separated by blank lines.

            Preserve the user's words and meaning. Do not summarise, paraphrase, or translate. If the transcript is already clean, return it unchanged.

            $profileLine
        """.trimIndent()
    }
}
