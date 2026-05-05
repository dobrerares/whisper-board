package com.whisperboard.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed repository for the user's language state. Three independent
 * concepts live here:
 *
 * - [favoriteLanguages] — a starred subset for the IME picker / Android subtype
 *   switcher. Cosmetic, no effect on transcription.
 * - [activeLanguage] — the language for the *next* utterance. Defaults to
 *   `"auto"` (whisper.cpp auto-detects). The user can pin a specific language
 *   via the chip; the chip's semantics are independent of the profile.
 * - [spokenLanguages] — the **language profile**: the set of languages the
 *   user has declared they speak. Consumed by [com.whisperboard.postprocessing.PromptBuilder]
 *   as system-prompt context for code-switching recovery (per
 *   `docs/adr/0003-multilingual-via-profile-and-llm.md`). The profile is
 *   **not** passed to whisper.cpp — Whisper's `language` parameter is a single
 *   string, and the profile flows only through the post-processor stage.
 *   Defaults to `setOf("auto")` so first-time / "skipped onboarding" users keep
 *   the existing zero-context behaviour.
 *
 * The constructor takes the underlying [DataStore] directly so the repository
 * can be unit-tested with an in-memory store. Production code uses the
 * [Context]-based secondary constructor that points at the app-wide
 * `appDataStore`.
 */
class LanguageRepository(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.appDataStore)

    companion object {
        private val KEY_FAVORITES = stringSetPreferencesKey("favorite_languages")
        private val KEY_ACTIVE_LANGUAGE = stringPreferencesKey("active_language")
        private val KEY_SPOKEN_LANGUAGES = stringSetPreferencesKey("spoken_languages")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("language_onboarding_complete")

        /**
         * Default profile for users who have not declared anything yet (or who
         * skipped onboarding). `[auto]` preserves the pre-profile behaviour:
         * the post-processor's prompt says "language not declared" and Whisper
         * keeps `language=auto`. Centralised here so callers / tests don't
         * hard-code the default.
         */
        val DEFAULT_SPOKEN_LANGUAGES: Set<String> = setOf("auto")
    }

    val favoriteLanguages: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_FAVORITES] ?: emptySet()
    }

    val activeLanguage: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LANGUAGE] ?: "auto"
    }

    /**
     * The user's declared language profile. Mirrors the [activeLanguage] API —
     * a [Flow] read side and a `set*` write side. Default is
     * [DEFAULT_SPOKEN_LANGUAGES] (`{"auto"}`).
     */
    val spokenLanguages: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SPOKEN_LANGUAGES] ?: DEFAULT_SPOKEN_LANGUAGES
    }

    /**
     * Whether the user has completed the first-launch language-profile prompt
     * (or explicitly skipped it). Defaults to `false` so a fresh install
     * surfaces the onboarding screen exactly once. Persisted alongside the
     * profile itself so a "skip" still records the user's choice.
     */
    val onboardingComplete: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETE] ?: false
    }

    suspend fun setActiveLanguage(code: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LANGUAGE] = code
        }
    }

    /**
     * Replace the profile with [codes]. Empty input collapses to
     * [DEFAULT_SPOKEN_LANGUAGES] so callers can't accidentally produce an
     * "no profile at all" state distinct from the default.
     */
    suspend fun setSpokenLanguages(codes: Set<String>) {
        val effective = if (codes.isEmpty()) DEFAULT_SPOKEN_LANGUAGES else codes
        dataStore.edit { prefs ->
            prefs[KEY_SPOKEN_LANGUAGES] = effective
        }
    }

    /**
     * Mark the first-launch onboarding as complete. Independent of whether the
     * user actually picked any languages — "skip" should still flip this flag
     * so the prompt does not re-appear.
     */
    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETE] = complete
        }
    }

    suspend fun addFavorite(code: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current + code
        }
    }

    suspend fun removeFavorite(code: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current - code
        }
    }
}
