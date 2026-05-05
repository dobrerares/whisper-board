package com.whisperboard.postprocessing

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for [ApiPostProcessor] against a [MockWebServer]. We
 * verify wire format (path, headers, body shape) and response handling
 * (success, non-2xx, malformed body, network error).
 */
class ApiPostProcessorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newProcessor(
        baseUrl: String = server.url("/v1").toString(),
        apiKey: String = "sk-test",
        model: String = "gpt-4o-mini",
    ) = ApiPostProcessor(
        client = client,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
    )

    // --- success path ---

    @Test
    fun `polish returns trimmed content from a well-formed response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"choices":[{"message":{"content":"  First, I went to the store.  "}}]}"""
                )
        )

        val result = newProcessor().polish(
            rawTranscript = "um first I went to the store",
            context = PostProcessingContext(),
        )

        assertEquals("First, I went to the store.", result)
    }

    @Test
    fun `polish posts to chat-completions with bearer auth and the prompt pair`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}""")
        )
        val raw = "um yeah ok"
        newProcessor().polish(raw, PostProcessingContext())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(
            "expected /chat/completions in path; got ${recorded.path}",
            recorded.path?.endsWith("/v1/chat/completions") == true,
        )
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("gpt-4o-mini", body.getString("model"))

        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        val system = messages.getJSONObject(0)
        val user = messages.getJSONObject(1)
        assertEquals("system", system.getString("role"))
        assertEquals("user", user.getString("role"))
        // user content is the raw transcript verbatim — same contract
        // [PromptBuilderTest] asserts on.
        assertEquals(raw, user.getString("content"))
        // system content is the Tier 2 contract — at least one signature
        // string must be present.
        assertTrue(
            "expected Tier 2 system prompt; got ${system.getString("content")}",
            system.getString("content").contains("transcript polisher", ignoreCase = true),
        )
    }

    @Test
    fun `polish omits Authorization header when api key is blank`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}""")
        )
        newProcessor(apiKey = "").polish(
            rawTranscript = "x",
            context = PostProcessingContext(),
        )
        val recorded = server.takeRequest()
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test
    fun `polish returns blank input untouched without hitting the network`() = runTest {
        // no enqueued response — if the processor tried to fetch, the
        // request would block until timeout and fail.
        val result = newProcessor().polish("   ", PostProcessingContext())
        assertEquals("   ", result)
        assertEquals(0, server.requestCount)
    }

    // --- failure paths ---

    @Test
    fun `polish throws PostProcessingException on non-2xx response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":"upstream"}""")
        )
        try {
            newProcessor().polish("x", PostProcessingContext())
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertTrue(
                "exception message should mention status code; got ${e.message}",
                e.message?.contains("500") == true,
            )
        }
    }

    @Test
    fun `polish throws PostProcessingException on malformed JSON`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("not json")
        )
        try {
            newProcessor().polish("x", PostProcessingContext())
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `polish throws PostProcessingException when choices is missing`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"chatcmpl-x"}""")
        )
        try {
            newProcessor().polish("x", PostProcessingContext())
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertTrue(
                "expected message about choices; got ${e.message}",
                e.message?.contains("choices") == true,
            )
        }
    }

    @Test
    fun `polish throws PostProcessingException when content is empty`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":""}}]}""")
        )
        try {
            newProcessor().polish("x", PostProcessingContext())
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertTrue(
                "expected message about empty content; got ${e.message}",
                e.message?.contains("content") == true,
            )
        }
    }

    @Test
    fun `polish throws PostProcessingException on network error`() = runTest {
        // Point the processor at a port that's been closed by shutting
        // down the server; the next request will fail to connect.
        server.shutdown()
        try {
            newProcessor().polish("x", PostProcessingContext())
            fail("expected PostProcessingException")
        } catch (e: PostProcessingException) {
            assertNotNull(e.message)
        }
    }
}
