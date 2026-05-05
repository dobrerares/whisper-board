package com.whisperboard.model.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Retention values offered to the user. The brief calls for **off / 25 / 100
 * / 500**.
 *
 * - [Off] is the explicit "do not persist" state. The repository writes are
 *   gated on it, so the database stays empty.
 * - [Twenty5] / [OneHundred] / [FiveHundred] cap the database size; the
 *   underlying Dao prunes on every insert.
 *
 * Stored as the limit value (or 0 for off) in DataStore — a small enum keeps
 * the wire format human-readable in DataStore dumps.
 */
enum class HistoryRetention(val limit: Int) {
    Off(limit = 0),
    Twenty5(limit = 25),
    OneHundred(limit = 100),
    FiveHundred(limit = 500),
    ;

    val isEnabled: Boolean
        get() = this != Off

    companion object {
        fun fromLimit(limit: Int): HistoryRetention =
            entries.firstOrNull { it.limit == limit } ?: OneHundred
    }
}

/**
 * DataStore-backed Privacy / History settings. Three independent knobs:
 *
 * - [retention] — how many dictation entries to keep. Default 100, off
 *   disables persistence entirely.
 * - [sendTranscriptsToRemoteLlm] — master switch for the
 *   `ApiPostProcessor` strategy. Default ON (the existing behaviour;
 *   slice 1 set up the strategy picker but did not gate it).
 * - [logDiagnosticsEnabled] — master switch for any in-app diagnostic
 *   logging beyond the standard `android.util.Log` plumbing. Default OFF
 *   per the brief.
 *
 * Defaults preserve the current product behaviour: retention 100 (so the
 * IME's history scroll has content out of the box) and "send to LLM" ON
 * (the polish stage was already shipping pre-slice-6b). Diagnostics OFF is
 * the conservative privacy default.
 */
class HistorySettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.appDataStore)

    companion object {
        private val KEY_RETENTION_LIMIT = intPreferencesKey("history_retention_limit")
        private val KEY_SEND_TO_LLM = booleanPreferencesKey("history_send_transcripts_to_llm")
        private val KEY_LOG_DIAGNOSTICS = booleanPreferencesKey("history_log_diagnostics")

        val DEFAULT_RETENTION = HistoryRetention.OneHundred
        const val DEFAULT_SEND_TRANSCRIPTS_TO_REMOTE_LLM = true
        const val DEFAULT_LOG_DIAGNOSTICS = false
    }

    val retention: Flow<HistoryRetention> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_RETENTION_LIMIT]
        if (stored == null) DEFAULT_RETENTION else HistoryRetention.fromLimit(stored)
    }

    val sendTranscriptsToRemoteLlm: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SEND_TO_LLM] ?: DEFAULT_SEND_TRANSCRIPTS_TO_REMOTE_LLM
    }

    val logDiagnosticsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LOG_DIAGNOSTICS] ?: DEFAULT_LOG_DIAGNOSTICS
    }

    suspend fun setRetention(value: HistoryRetention) {
        dataStore.edit { it[KEY_RETENTION_LIMIT] = value.limit }
    }

    suspend fun setSendTranscriptsToRemoteLlm(enabled: Boolean) {
        dataStore.edit { it[KEY_SEND_TO_LLM] = enabled }
    }

    suspend fun setLogDiagnosticsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_DIAGNOSTICS] = enabled }
    }
}
