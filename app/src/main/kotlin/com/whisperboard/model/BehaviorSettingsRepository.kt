package com.whisperboard.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings for behavioural toggles that govern how transcripts
 * reach the user. Two independent switches:
 *
 * - **Auto-insert** — when on (default), the polished transcript is committed
 *   into the focused field via `InputConnection.commitText` as soon as
 *   transcription completes. When off, the existing preview-then-commit flow
 *   is restored: the IME shows the transcript and the user taps to send it.
 *
 * - **Auto-copy** — when on (default), the bubble's standalone mode auto-copies
 *   the polished transcript to the system clipboard. Independent of
 *   auto-insert; the bubble surface is gated by issue #3.
 *
 * Both names mirror the vocabulary in `CONTEXT.md` exactly. Defaults are ON to
 * preserve the "speak anywhere, get clean text" pitch.
 *
 * The constructor takes the underlying [DataStore] directly so the repository
 * can be unit-tested with an in-memory store. Production code uses the
 * [Context]-based secondary constructor that points at the app-wide
 * `appDataStore`.
 */
class BehaviorSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.appDataStore)

    companion object {
        private val KEY_AUTO_INSERT = booleanPreferencesKey("auto_insert_enabled")
        private val KEY_AUTO_COPY = booleanPreferencesKey("auto_copy_enabled")

        const val DEFAULT_AUTO_INSERT = true
        const val DEFAULT_AUTO_COPY = true
    }

    val autoInsertEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_INSERT] ?: DEFAULT_AUTO_INSERT
    }

    val autoCopyEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_COPY] ?: DEFAULT_AUTO_COPY
    }

    suspend fun setAutoInsertEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_INSERT] = enabled }
    }

    suspend fun setAutoCopyEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_COPY] = enabled }
    }
}
