package com.whisperboard.postprocessing

import com.whisperboard.model.WhisperLanguages

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
 * The `languageProfile` is consumed unconditionally — even for monolingual
 * users — because the LLM gets proper-noun-spelling hints out of it and the
 * extra tokens are a rounding error. For multilingual profiles
 * (`size > 1` and not just the `auto` sentinel) the prompt also instructs the
 * LLM to recover phonetically-mangled phrases produced when one language is
 * transcribed using another's spelling. This is the v1 code-switching
 * recovery story per `docs/adr/0003-multilingual-via-profile-and-llm.md`.
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
        return """
            You are a transcript polisher. The user dictated text and a speech-to-text engine produced the transcript below. Your job is to clean it up and return ONLY the polished text — no commentary, no quoting, no preamble.

            Apply these passes in order:
            1. Remove filler words ("um", "uh", "like", "you know") and obvious false starts (repeated half-words, restarts after a stutter).
            2. Fix punctuation and capitalization. Capitalize sentence starts and proper nouns; add commas, periods, and question marks where the spoken cadence clearly implies them.
            3. Detect spoken lists ("first... second... third...", "one,... two,... three,...") and emit them as a Markdown bullet list.
            4. Detect paragraph boundaries from clear pauses or topic shifts and split long blocks into paragraphs separated by blank lines.

            Preserve the user's words and meaning. Do not summarise, paraphrase, or translate. If the transcript is already clean, return it unchanged.

            ${languageProfileSection(languageProfile)}
        """.trimIndent()
    }

    /**
     * The language-profile chunk of the system prompt. Three shapes:
     *
     * - **Empty / not-meaningful profile** (no codes, or only the `auto`
     *   sentinel): "not declared" — the LLM has no signal and behaves as it
     *   did pre-profile.
     * - **Monolingual** (one declared language, ignoring `auto`): name it.
     *   Useful for proper-noun-spelling hints.
     * - **Multilingual** (two or more declared languages, ignoring `auto`):
     *   name them and add the explicit code-switching recovery clause from
     *   ADR-0003 — "if a phrase appears phonetically transcribed from one
     *   language using another's spelling, restore it to its native
     *   language."
     *
     * The `auto` sentinel is filtered out because it is a UI default, not a
     * declared language; including it in the prompt would muddy the
     * recovery logic.
     */
    private fun languageProfileSection(profile: Set<String>): String {
        val declared = profile.filter { it != "auto" }
        return when {
            declared.isEmpty() -> "The user's spoken languages are not declared; do not assume a language."
            declared.size == 1 -> {
                val name = WhisperLanguages.displayName(declared.single())
                "The user speaks $name. Use this when restoring proper-noun spelling and idiom; preserve their words and meaning."
            }
            else -> {
                val names = declared.map { WhisperLanguages.displayName(it) }.sorted()
                val joined = names.joinToString(", ")
                "The user speaks: $joined. Speech-to-text may have transcribed a phrase from one of these languages using another's spelling (a phonetic artifact of code-switching). When you spot such a phrase, restore it to its native language and spelling. Use the profile to disambiguate proper-noun spelling. Preserve the user's words and meaning; do not translate one language's phrase into another."
            }
        }
    }
}
