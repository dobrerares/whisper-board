package com.whisperboard.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the language-profile and onboarding-complete extensions. The
 * contract:
 *
 * - `spokenLanguages` defaults to `{"auto"}` — preserves pre-profile behaviour
 *   for first-time users and for users who skip onboarding.
 * - `setSpokenLanguages` replaces the set wholesale; passing an empty set
 *   collapses to the default rather than producing a degenerate "no profile"
 *   state distinct from skip.
 * - The profile and the active language are independent: setting one must
 *   not mutate the other (per CONTEXT.md and ADR-0003).
 * - The profile and favourites are independent — favourites are cosmetic IME
 *   chrome, the profile is post-processor input.
 * - `onboardingComplete` defaults to `false`, persists across reads, and is
 *   independent of profile content (skipping still flips it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanguageRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreJob: Job
    private lateinit var repository: LanguageRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("language-prefs-test").toFile()
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
        dataStoreJob.cancel()
        dataStoreScope.cancel()
        tempDir.deleteRecursively()
    }

    // --- spokenLanguages defaults ---

    @Test
    fun `spokenLanguages default is the auto singleton`() = runTest {
        assertEquals(setOf("auto"), repository.spokenLanguages.first())
        assertEquals(setOf("auto"), LanguageRepository.DEFAULT_SPOKEN_LANGUAGES)
    }

    @Test
    fun `setSpokenLanguages persists across reads`() = runTest {
        repository.setSpokenLanguages(setOf("en", "ro"))
        assertEquals(setOf("en", "ro"), repository.spokenLanguages.first())

        repository.setSpokenLanguages(setOf("ja"))
        assertEquals(setOf("ja"), repository.spokenLanguages.first())
    }

    @Test
    fun `setSpokenLanguages with empty set collapses to default`() = runTest {
        // Guard against callers accidentally clearing the profile to a state
        // that's distinct from skip — `[auto]` is the canonical default.
        repository.setSpokenLanguages(setOf("en"))
        repository.setSpokenLanguages(emptySet())
        assertEquals(LanguageRepository.DEFAULT_SPOKEN_LANGUAGES, repository.spokenLanguages.first())
    }

    // --- profile vs active language independence ---

    @Test
    fun `setActiveLanguage does not mutate spokenLanguages`() = runTest {
        repository.setSpokenLanguages(setOf("en", "ro"))
        repository.setActiveLanguage("ja")
        assertEquals(setOf("en", "ro"), repository.spokenLanguages.first())
        assertEquals("ja", repository.activeLanguage.first())
    }

    @Test
    fun `setSpokenLanguages does not mutate activeLanguage`() = runTest {
        repository.setActiveLanguage("ja")
        repository.setSpokenLanguages(setOf("en", "ro"))
        assertEquals("ja", repository.activeLanguage.first())
    }

    // --- profile vs favourites independence ---

    @Test
    fun `setSpokenLanguages does not mutate favorites`() = runTest {
        repository.addFavorite("fr")
        repository.setSpokenLanguages(setOf("en", "ro"))
        assertEquals(setOf("fr"), repository.favoriteLanguages.first())
    }

    @Test
    fun `addFavorite does not mutate spokenLanguages`() = runTest {
        repository.setSpokenLanguages(setOf("en", "ro"))
        repository.addFavorite("fr")
        assertEquals(setOf("en", "ro"), repository.spokenLanguages.first())
    }

    // --- onboarding flag ---

    @Test
    fun `onboardingComplete default is false`() = runTest {
        assertFalse(repository.onboardingComplete.first())
    }

    @Test
    fun `setOnboardingComplete persists`() = runTest {
        repository.setOnboardingComplete(true)
        assertTrue(repository.onboardingComplete.first())

        repository.setOnboardingComplete(false)
        assertFalse(repository.onboardingComplete.first())
    }

    @Test
    fun `skipping onboarding flips the flag without changing profile`() = runTest {
        // Simulate the "Skip" path — the user does not pick any languages but
        // we still mark onboarding as done so the prompt never re-appears.
        repository.setOnboardingComplete(true)
        assertTrue(repository.onboardingComplete.first())
        assertEquals(LanguageRepository.DEFAULT_SPOKEN_LANGUAGES, repository.spokenLanguages.first())
    }
}
