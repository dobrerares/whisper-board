package com.whisperboard.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `tier 2 with only the auto sentinel says language is not declared`() {
        // `[auto]` is the canonical default for users who skipped onboarding —
        // it must behave identically to an empty profile so we don't muddle
        // the LLM's signal with a UI sentinel.
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("auto"),
        )
        assertTrue(
            "auto-only profile must produce a 'not declared' line; got:\n${pair.system}",
            pair.system.contains("not declared", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with monolingual profile names the language by display name`() {
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en"),
        )
        assertTrue(
            "monolingual profile must mention the language by display name; got:\n${pair.system}",
            pair.system.contains("English"),
        )
        assertTrue(
            "monolingual profile must say the user speaks X; got:\n${pair.system}",
            pair.system.contains("speaks", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with monolingual profile does not include code-switching recovery clause`() {
        // Code-switching is meaningful only when the user speaks 2+ languages;
        // including the clause for a monolingual profile would invite hallucinated
        // "restorations" of fine English text into other scripts.
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en"),
        )
        assertFalse(
            "monolingual profile must NOT include code-switching recovery clause; got:\n${pair.system}",
            pair.system.contains("phonetic", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with multilingual profile lists the languages by display name`() {
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en", "ro"),
        )
        assertTrue(
            "multilingual profile must include English; got:\n${pair.system}",
            pair.system.contains("English"),
        )
        assertTrue(
            "multilingual profile must include Romanian; got:\n${pair.system}",
            pair.system.contains("Romanian"),
        )
    }

    @Test
    fun `tier 2 with multilingual profile includes the code-switching recovery clause`() {
        // Per ADR-0003: the LLM must be told that a phonetically-mangled phrase
        // could be a code-switched word from another declared language and to
        // restore it. This is the entire point of the language profile.
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en", "ro"),
        )
        assertTrue(
            "multilingual profile must mention 'phonetic' transcription artifact; got:\n${pair.system}",
            pair.system.contains("phonetic", ignoreCase = true),
        )
        assertTrue(
            "multilingual profile must instruct the LLM to restore phrases to their native language; got:\n${pair.system}",
            pair.system.contains("restore", ignoreCase = true),
        )
    }

    @Test
    fun `tier 2 with multilingual profile filters out the auto sentinel`() {
        // `auto` is the chip default; it should not pollute the declared-set
        // count. A profile of `[auto, en]` is semantically monolingual.
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("auto", "en"),
        )
        assertFalse(
            "auto-plus-en is monolingual; must NOT include code-switching clause; got:\n${pair.system}",
            pair.system.contains("phonetic", ignoreCase = true),
        )
    }

    @Test
    fun `monolingual profile produces a different system prompt than empty profile`() {
        val empty = PromptBuilder.build(rawTranscript = "x", languageProfile = emptySet())
        val mono = PromptBuilder.build(rawTranscript = "x", languageProfile = setOf("en"))
        assertNotEquals(empty.system, mono.system)
    }

    @Test
    fun `multilingual profile produces a different system prompt than monolingual`() {
        val mono = PromptBuilder.build(rawTranscript = "x", languageProfile = setOf("en"))
        val multi = PromptBuilder.build(rawTranscript = "x", languageProfile = setOf("en", "ro"))
        assertNotEquals(mono.system, multi.system)
    }

    @Test
    fun `tier 2 system prompt forbids cross-language translation in the multilingual case`() {
        // The clause must instruct restoration, not translation — translation
        // is S3 in the multilingual taxonomy and is explicitly out of scope
        // for v1 per ADR-0003.
        val pair = PromptBuilder.build(
            rawTranscript = "anything",
            languageProfile = setOf("en", "ro"),
        )
        assertTrue(
            "multilingual prompt must still forbid translation; got:\n${pair.system}",
            pair.system.contains("not translate", ignoreCase = true) ||
                pair.system.contains("do not translate", ignoreCase = true),
        )
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
