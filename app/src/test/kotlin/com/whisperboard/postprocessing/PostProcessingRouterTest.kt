package com.whisperboard.postprocessing

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers each [PostProcessingStrategy] × (api-available, api-unavailable)
 * combination. The router's contract:
 * - Never throws on polish failure.
 * - Never returns empty text when given non-empty input.
 * - When polish mode is off, returns [PostProcessingOutcome.Skipped].
 * - When the strategy is unroutable in this slice, returns
 *   [PostProcessingOutcome.Skipped].
 * - On polish failure, returns [PostProcessingOutcome.Fallback] with the raw
 *   transcript and a short reason.
 */
class PostProcessingRouterTest {

    private val context = PostProcessingContext()
    private val raw = "um so first I went to the store"

    private fun newRouter(
        polishMode: Boolean = true,
        strategy: PostProcessingStrategy = PostProcessingStrategy.OFF,
        apiPostProcessor: ApiPostProcessor? = null,
    ): PostProcessingRouter {
        val router = PostProcessingRouter(
            polishModeProvider = { polishMode },
            strategyProvider = { strategy },
        )
        router.apiPostProcessor = apiPostProcessor
        return router
    }

    private fun successApiPostProcessor(polished: String = "Polished."): ApiPostProcessor =
        // We use a real ApiPostProcessor pointed at a URL we won't hit because
        // the router's polish path delegates to processor.polish() — we test
        // the wire format end-to-end in ApiPostProcessorTest. For pure router
        // tests, a tiny FakePostProcessor is clearer, but the brief's
        // contract is "sealed PostProcessor with implementations" — so we
        // model success/failure via the API processor against MockWebServer
        // in ApiPostProcessorTest, and here we exercise the router's
        // strategy decisions with a stub ApiPostProcessor that we build the
        // same way the IME does.
        StubApiPostProcessor(polishedReturn = polished)

    private fun failingApiPostProcessor(reason: String = "boom"): ApiPostProcessor =
        StubApiPostProcessor(throwReason = reason)

    // --- polish mode off ---

    @Test
    fun `polish mode off returns Skipped regardless of strategy`() = runTest {
        for (strategy in PostProcessingStrategy.entries) {
            val router = newRouter(
                polishMode = false,
                strategy = strategy,
                apiPostProcessor = successApiPostProcessor(),
            )
            val outcome = router.polish(raw, context)
            assertTrue(
                "expected Skipped for strategy=$strategy when polish mode is off; got $outcome",
                outcome is PostProcessingOutcome.Skipped,
            )
            assertEquals(raw, (outcome as PostProcessingOutcome.Skipped).text)
        }
    }

    // --- empty / blank input ---

    @Test
    fun `blank input returns Skipped without invoking processor`() = runTest {
        val router = newRouter(
            polishMode = true,
            strategy = PostProcessingStrategy.API_ONLY,
            apiPostProcessor = failingApiPostProcessor(),
        )
        val outcome = router.polish("   ", context)
        assertTrue(outcome is PostProcessingOutcome.Skipped)
    }

    // --- OFF ---

    @Test
    fun `OFF with api available returns Skipped`() = runTest {
        val router = newRouter(
            strategy = PostProcessingStrategy.OFF,
            apiPostProcessor = successApiPostProcessor(),
        )
        val outcome = router.polish(raw, context)
        assertTrue(outcome is PostProcessingOutcome.Skipped)
        assertEquals(raw, (outcome as PostProcessingOutcome.Skipped).text)
    }

    @Test
    fun `OFF with api unavailable returns Skipped`() = runTest {
        val router = newRouter(
            strategy = PostProcessingStrategy.OFF,
            apiPostProcessor = null,
        )
        val outcome = router.polish(raw, context)
        assertTrue(outcome is PostProcessingOutcome.Skipped)
    }

    // --- API_ONLY ---

    @Test
    fun `API_ONLY with api available returns Polished`() = runTest {
        val polished = "First, I went to the store."
        val router = newRouter(
            strategy = PostProcessingStrategy.API_ONLY,
            apiPostProcessor = successApiPostProcessor(polished),
        )
        val outcome = router.polish(raw, context)
        assertTrue(
            "expected Polished, got $outcome",
            outcome is PostProcessingOutcome.Polished,
        )
        assertEquals(polished, (outcome as PostProcessingOutcome.Polished).text)
    }

    @Test
    fun `API_ONLY with api unavailable returns Fallback with raw text`() = runTest {
        val router = newRouter(
            strategy = PostProcessingStrategy.API_ONLY,
            apiPostProcessor = null,
        )
        val outcome = router.polish(raw, context)
        assertTrue(outcome is PostProcessingOutcome.Fallback)
        outcome as PostProcessingOutcome.Fallback
        assertEquals(raw, outcome.text)
        assertTrue(outcome.reason.isNotBlank())
    }

    @Test
    fun `API_ONLY with failing processor returns Fallback with raw text`() = runTest {
        val router = newRouter(
            strategy = PostProcessingStrategy.API_ONLY,
            apiPostProcessor = failingApiPostProcessor(reason = "network down"),
        )
        val outcome = router.polish(raw, context)
        assertTrue(outcome is PostProcessingOutcome.Fallback)
        outcome as PostProcessingOutcome.Fallback
        assertEquals(raw, outcome.text)
        // Reason is propagated for telemetry / surface — we don't pin the
        // exact wording.
        assertNotEquals("", outcome.reason)
    }

    // --- LOCAL_* and API_WHEN_ONLINE: not yet routable ---

    @Test
    fun `LOCAL_ONLY falls through to Skipped until slice 4 lands`() = runTest {
        for (apiCase in listOf(successApiPostProcessor(), null)) {
            val router = newRouter(
                strategy = PostProcessingStrategy.LOCAL_ONLY,
                apiPostProcessor = apiCase,
            )
            val outcome = router.polish(raw, context)
            assertTrue(
                "LOCAL_ONLY must fall through to Skipped (api=$apiCase); got $outcome",
                outcome is PostProcessingOutcome.Skipped,
            )
        }
    }

    @Test
    fun `LOCAL_PREFERRED falls through to Skipped until slice 4 lands`() = runTest {
        for (apiCase in listOf(successApiPostProcessor(), null)) {
            val router = newRouter(
                strategy = PostProcessingStrategy.LOCAL_PREFERRED,
                apiPostProcessor = apiCase,
            )
            val outcome = router.polish(raw, context)
            assertTrue(
                "LOCAL_PREFERRED must fall through to Skipped (api=$apiCase); got $outcome",
                outcome is PostProcessingOutcome.Skipped,
            )
        }
    }

    @Test
    fun `API_WHEN_ONLINE falls through to Skipped until slice 4 lands`() = runTest {
        for (apiCase in listOf(successApiPostProcessor(), null)) {
            val router = newRouter(
                strategy = PostProcessingStrategy.API_WHEN_ONLINE,
                apiPostProcessor = apiCase,
            )
            val outcome = router.polish(raw, context)
            assertTrue(
                "API_WHEN_ONLINE must fall through to Skipped (api=$apiCase); got $outcome",
                outcome is PostProcessingOutcome.Skipped,
            )
        }
    }

    // --- words-never-lost invariant ---

    @Test
    fun `every outcome contains non-empty text when input is non-empty`() = runTest {
        val variants = listOf(
            newRouter(strategy = PostProcessingStrategy.OFF),
            newRouter(strategy = PostProcessingStrategy.API_ONLY, apiPostProcessor = null),
            newRouter(strategy = PostProcessingStrategy.API_ONLY, apiPostProcessor = failingApiPostProcessor()),
            newRouter(strategy = PostProcessingStrategy.API_ONLY, apiPostProcessor = successApiPostProcessor()),
            newRouter(strategy = PostProcessingStrategy.LOCAL_ONLY, apiPostProcessor = null),
            newRouter(strategy = PostProcessingStrategy.LOCAL_PREFERRED, apiPostProcessor = null),
            newRouter(strategy = PostProcessingStrategy.API_WHEN_ONLINE, apiPostProcessor = null),
        )
        for (router in variants) {
            val outcome = router.polish(raw, context)
            val text = when (outcome) {
                is PostProcessingOutcome.Polished -> outcome.text
                is PostProcessingOutcome.Skipped -> outcome.text
                is PostProcessingOutcome.Fallback -> outcome.text
            }
            assertTrue("words must never be lost; got empty for $outcome", text.isNotEmpty())
        }
    }
}

/**
 * Test double for [ApiPostProcessor] used by [PostProcessingRouterTest]. We
 * subclass [ApiPostProcessor] directly so the router's `apiPostProcessor`
 * slot accepts it; the constructor args are inert because we override
 * [polish] to skip the network entirely.
 */
private class StubApiPostProcessor(
    private val polishedReturn: String? = null,
    private val throwReason: String? = null,
) : ApiPostProcessor(
    client = OkHttpClient(),
    baseUrl = "http://stub",
    apiKey = "",
    model = "stub",
) {
    override suspend fun polish(rawTranscript: String, context: PostProcessingContext): String {
        if (throwReason != null) throw PostProcessingException(throwReason)
        return polishedReturn ?: error("StubApiPostProcessor needs polishedReturn or throwReason")
    }
}
