package com.whisperboard.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 prompt is the contract — these assertions exist so prompt drift is
 * caught loudly. If you change the system prompt, update these assertions
 * with intent.
 */
class PromptBuilderTest {

    @Test
    fun `tier 2 user prompt is the raw transcript verbatim`() {
        val raw = "um so first I went to the store and then uh"
        val pair = PromptBuilder.build(rawTranscript = raw, tier = PostProcessingTier.TIER_2)
        assertEquals(raw, pair.user)
    }

    @Test
    fun `tier 2 system prompt names itself a transcript polisher`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "expected system prompt to identify the role; got:\n${pair.system}",
            pair.system.contains("transcript polisher", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt instructs filler removal`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "expected system prompt to mention filler words; got:\n${pair.system}",
            pair.system.contains("filler", ignoreCase = true),
        )
        assertTrue(
            "expected system prompt to mention false starts; got:\n${pair.system}",
            pair.system.contains("false start", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt instructs punctuation and capitalization`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "expected punctuation guidance; got:\n${pair.system}",
            pair.system.contains("punctuation", ignoreCase = true),
        )
        assertTrue(
            "expected capitalization guidance; got:\n${pair.system}",
            pair.system.contains("capitali", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt instructs list detection`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "expected list-detection guidance; got:\n${pair.system}",
            pair.system.contains("list", ignoreCase = true),
        )
        assertTrue(
            "expected bullet output guidance; got:\n${pair.system}",
            pair.system.contains("bullet", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt instructs paragraph detection`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "expected paragraph guidance; got:\n${pair.system}",
            pair.system.contains("paragraph", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt forbids summarisation translation paraphrasing`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        // Each of these is critical to preserving the user's words.
        assertTrue(
            "system prompt must forbid summarising; got:\n${pair.system}",
            pair.system.contains("summari", ignoreCase = true),
        )
        assertTrue(
            "system prompt must forbid paraphrasing; got:\n${pair.system}",
            pair.system.contains("paraphrase", ignoreCase = true),
        )
        assertTrue(
            "system prompt must forbid translating; got:\n${pair.system}",
            pair.system.contains("translate", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 system prompt instructs returning ONLY the polished text`() {
        val pair = PromptBuilder.build(rawTranscript = "anything")
        assertTrue(
            "system prompt must tell the LLM to return only the polished text; got:\n${pair.system}",
            pair.system.contains("only", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with empty profile says language is not declared`() {
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = emptySet(),
        )
        assertTrue(
            "empty profile must produce a 'not declared' line; got:\n${pair.system}",
            pair.system.contains("not declared", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with non-empty profile lists the languages`() {
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en", "ro"),
        )
        // The profile parameter is wired but treated as placeholder per the
        // brief — the actual code-switching context lands in slice 5. Until
        // then the builder simply names the languages so the prompt is
        // self-consistent.
        assertTrue(
            "non-empty profile must produce a 'speaks' line; got:\n${pair.system}",
            pair.system.contains("speaks", ignoreCase = true),
        )
        assertTrue(
            "non-empty profile must mention en; got:\n${pair.system}",
            pair.system.contains("en"),
        )
        assertTrue(
            "non-empty profile must mention ro; got:\n${pair.system}",
            pair.system.contains("ro"),
        )
    }

    @Test
    fun `monolingual profile produces a different system prompt than empty profile`() {
        val empty = PromptBuilder.build(rawTranscript = "x", languageProfile = emptySet())
        val mono = PromptBuilder.build(rawTranscript = "x", languageProfile = setOf("en"))
        assertNotEquals(empty.system, mono.system)
    }

    @Test
    fun `default tier is tier 2`() {
        val explicit = PromptBuilder.build(
            rawTranscript = "x",
            tier = PostProcessingTier.TIER_2,
        )
        val implicit = PromptBuilder.build(rawTranscript = "x")
        assertEquals(explicit, implicit)
    }
}
