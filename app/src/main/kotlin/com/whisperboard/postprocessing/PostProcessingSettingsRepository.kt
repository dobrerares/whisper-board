package com.whisperboard.postprocessing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings for the post-processing stage. Holds:
 * - the master "polish mode" toggle (default: on),
 * - the strategy choice (default: OFF).
 *
 * Endpoint / auth config is intentionally NOT duplicated here — the
 * post-processor reuses `ApiSettingsRepository` for that surface so users
 * don't re-enter base URL / API key / provider for the polish stage.
 */
class PostProcessingSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_POLISH_MODE = booleanPreferencesKey("polish_mode_enabled")
        private val KEY_STRATEGY = stringPreferencesKey("post_processing_strategy")

        // Polish mode is auto-on by default per CONTEXT.md.
        const val DEFAULT_POLISH_MODE = true
        val DEFAULT_STRATEGY = PostProcessingStrategy.OFF
    }

    val polishModeEnabled: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[KEY_POLISH_MODE] ?: DEFAULT_POLISH_MODE
    }

    val strategy: Flow<PostProcessingStrategy> = context.appDataStore.data.map { prefs ->
        val name = prefs[KEY_STRATEGY] ?: DEFAULT_STRATEGY.name
        runCatching { PostProcessingStrategy.valueOf(name) }.getOrDefault(DEFAULT_STRATEGY)
    }

    suspend fun setPolishModeEnabled(enabled: Boolean) {
        context.appDataStore.edit { it[KEY_POLISH_MODE] = enabled }
    }

    suspend fun setStrategy(strategy: PostProcessingStrategy) {
        context.appDataStore.edit { it[KEY_STRATEGY] = strategy.name }
    }
}
