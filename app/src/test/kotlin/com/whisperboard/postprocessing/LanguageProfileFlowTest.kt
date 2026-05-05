package com.whisperboard.postprocessing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.whisperboard.model.LanguageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * End-to-end coverage of the data path the slice 5 brief calls out:
 * **`LanguageRepository.spokenLanguages` -> `PostProcessingContext` ->
 * `PromptBuilder` -> `ApiPostProcessor` request body.**
 *
 * Each test writes a profile through the *real* repository, builds the
 * context the way `KeyboardViewModel` does (snapshotting the flow), passes
 * it into `ApiPostProcessor`, and inspects the request body the
 * post-processor sent to a [MockWebServer]. This pins the contract that the
 * profile reaches the LLM verbatim — there is no path between the user's
 * declared profile and the on-the-wire system prompt that we don't cover
 * here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageProfileFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreJob: Job
    private lateinit var repository: LanguageRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        tempDir = Files.createTempDirectory("language-profile-flow-test").toFile()
        dataStoreJob = SupervisorJob()
        dataStoreScope = CoroutineScope(UnconfinedTestDispatcher() + dataStoreJob)
        val store = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(tempDir, "language_prefs.preferences_pb") },
        )
        repository = LanguageRepository(store)
    }

    @After
    fun tearDown() {
        server.shutdown()
        dataStoreJob.cancel()
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    private fun newProcessor(): ApiPostProcessor = ApiPostProcessor(
        client = client,
        baseUrl = server.url("/v1").toString(),
        apiKey = "sk-test",
        model = "gpt-4o-mini",
    )

    private fun enqueueOk() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
        )
    }

    /**
     * Drive `polish` and pull the system-prompt content out of the recorded
     * request body. This is the single hot loop for every assertion below.
     */
    private suspend fun runPolishAndCaptureSystemPrompt(): String {
        enqueueOk()
        val profile = repository.spokenLanguages.first()
        val context = PostProcessingContext(languageProfile = profile)
        newProcessor().polish(rawTranscript = "anything", context = context)

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        val messages = body.getJSONArray("messages")
        return messages.getJSONObject(0).getString("content")
    }

    // --- default profile path ---

    @Test
    fun `default repository profile produces a not-declared system prompt`() = runTest {
        // No writes — the repository default is `[auto]`, which the
        // PromptBuilder maps to "not declared".
        val systemPrompt = runPolishAndCaptureSystemPrompt()
        assertTrue(
            "default profile must surface as 'not declared' on the wire; got:\n$systemPrompt",
            systemPrompt.contains("not declared", ignoreCase = true),
        )
    }

    // --- monolingual profile path ---

    @Test
    fun `monolingual profile lands in the system prompt by display name`() = runTest {
        repository.setSpokenLanguages(setOf("en"))
        val systemPrompt = runPolishAndCaptureSystemPrompt()
        assertTrue(
            "monolingual profile must include English by display name; got:\n$systemPrompt",
            systemPrompt.contains("English"),
        )
        assertFalse(
            "monolingual profile must NOT include the code-switching clause; got:\n$systemPrompt",
            systemPrompt.contains("phonetic", ignoreCase = true),
        )
    }

    // --- multilingual profile path ---

    @Test
    fun `multilingual profile lands in the system prompt with the code-switching clause`() = runTest {
        repository.setSpokenLanguages(setOf("en", "ro"))
        val systemPrompt = runPolishAndCaptureSystemPrompt()

        // The list of languages is on the wire by display name.
        assertTrue(
            "multilingual profile must include English; got:\n$systemPrompt",
            systemPrompt.contains("English"),
        )
        assertTrue(
            "multilingual profile must include Romanian; got:\n$systemPrompt",
            systemPrompt.contains("Romanian"),
        )

        // ADR-0003's recovery clause is on the wire.
        assertTrue(
            "multilingual profile must mention 'phonetic' on the wire; got:\n$systemPrompt",
            systemPrompt.contains("phonetic", ignoreCase = true),
        )
        assertTrue(
            "multilingual profile must instruct restoration on the wire; got:\n$systemPrompt",
            systemPrompt.contains("restore", ignoreCase = true),
        )

        // ADR-0003 explicitly forbids translation in v1 — restoration is not
        // translation. The forbid clause must travel end-to-end.
        assertTrue(
            "multilingual profile must still forbid translation on the wire; got:\n$systemPrompt",
            systemPrompt.contains("not translate", ignoreCase = true) ||
                systemPrompt.contains("do not translate", ignoreCase = true),
        )
    }

    // --- auto sentinel handling on the wire ---

    @Test
    fun `auto-only profile produces a not-declared system prompt on the wire`() = runTest {
        // The user explicitly skipped onboarding and the default `[auto]` is
        // what the post-processor sees. The wire output must match the
        // "no profile" path so the LLM is not biased by a UI sentinel.
        repository.setSpokenLanguages(setOf("auto"))
        val systemPrompt = runPolishAndCaptureSystemPrompt()
        assertTrue(
            "auto-only profile must surface as 'not declared' on the wire; got:\n$systemPrompt",
            systemPrompt.contains("not declared", ignoreCase = true),
        )
    }

    @Test
    fun `auto plus one declared language is monolingual on the wire`() = runTest {
        // `auto` is a UI default; mixing it with an explicit declaration must
        // not bump the wire prompt up to multilingual.
        repository.setSpokenLanguages(setOf("auto", "en"))
        val systemPrompt = runPolishAndCaptureSystemPrompt()
        assertTrue(
            "auto+en is monolingual: must mention English; got:\n$systemPrompt",
            systemPrompt.contains("English"),
        )
        assertFalse(
            "auto+en is monolingual: must NOT include the code-switching clause; got:\n$systemPrompt",
            systemPrompt.contains("phonetic", ignoreCase = true),
        )
    }

    // --- profile updates propagate ---

    @Test
    fun `updating the profile updates the next request's system prompt`() = runTest {
        // Snapshotting through `spokenLanguages.first()` must reflect the
        // latest write. This guards against any caller accidentally caching
        // the profile and missing live edits in Settings.
        repository.setSpokenLanguages(setOf("en"))
        val first = runPolishAndCaptureSystemPrompt()
        assertTrue(first.contains("English"))
        assertFalse(first.contains("phonetic", ignoreCase = true))

        repository.setSpokenLanguages(setOf("en", "ro"))
        val second = runPolishAndCaptureSystemPrompt()
        assertTrue(second.contains("English"))
        assertTrue(second.contains("Romanian"))
        assertTrue(second.contains("phonetic", ignoreCase = true))
    }
}
