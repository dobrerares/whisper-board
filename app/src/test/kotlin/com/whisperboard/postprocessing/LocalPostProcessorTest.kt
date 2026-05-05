package com.whisperboard.postprocessing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [LocalPostProcessor]. The real [com.whisperboard.llm.LlmContext]
 * loads a native library on first reference and so cannot be instantiated
 * inside JVM tests — those scenarios are validated via on-device manual
 * smoke testing and via the [PostProcessingRouterTest] routing tests, which
 * subclass [LocalPostProcessor] to skip the native call entirely.
 *
 * This suite covers the slice of decision logic that runs *before* the
 * native call:
 *
 * - Blank input short-circuits without ever asking the factory for a
 *   context (no model load wasted on whitespace).
 * - Factory failure surfaces as [PostProcessingException] so the router
 *   reports it as a `Fallback` outcome and the user gets the raw transcript.
 *
 * The post-condition that the [com.whisperboard.llm.LlmContext] is `close()`d
 * on both success and failure (RAM-budget contract from the brief) is
 * enforced by the implementation's `try { ... } finally { close() }`
 * structure and by manual on-device verification.
 */
class LocalPostProcessorTest {

    private val context = PostProcessingContext()

    @Test
    fun `blank input short-circuits without invoking factory`() = runTest {
        var factoryCalls = 0
        val processor = LocalPostProcessor(
            llmContextFactory = {
                factoryCalls++
                error("factory should not be called")
            },
        )
        val out = processor.polish("   ", context)
        assertEquals("   ", out)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `factory failure surfaces as PostProcessingException`() = runTest {
        val processor = LocalPostProcessor(
            llmContextFactory = { throw IllegalStateException("model file missing") },
        )
        try {
            processor.polish("hello world", context)
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertTrue(
                "expected reason to mention load failure; got '${e.message}'",
                e.message?.contains("load", ignoreCase = true) == true ||
                    e.message?.contains("model", ignoreCase = true) == true,
            )
        }
    }
}
