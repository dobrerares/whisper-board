package com.whisperboard.bubble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings for the bubble surface.
 *
 * Two persisted shapes:
 * - **Visibility mode** — [BubbleVisibilityMode] enum with default
 *   [BubbleVisibilityMode.AlwaysVisible] per the brief.
 * - **Position** — `x`/`y` integers for the last-known overlay coordinates
 *   so the bubble re-appears where the user last left it. Persisted in the
 *   same DataStore so we don't multiply DataStore singletons.
 *
 * The repository takes the [DataStore] directly so unit tests can drop in an
 * in-memory store; production code uses the [Context]-based secondary
 * constructor that reuses the app-wide `appDataStore`.
 */
class BubbleSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.appDataStore)

    companion object {
        private val KEY_VISIBILITY = stringPreferencesKey("bubble_visibility_mode")
        private val KEY_X = intPreferencesKey("bubble_x")
        private val KEY_Y = intPreferencesKey("bubble_y")

        val DEFAULT_VISIBILITY = BubbleVisibilityMode.AlwaysVisible

        /** Sentinel meaning "no position saved yet". */
        const val UNSET_COORDINATE: Int = Int.MIN_VALUE
    }

    val visibilityMode: Flow<BubbleVisibilityMode> = dataStore.data.map { prefs ->
        prefs[KEY_VISIBILITY]?.let { stored ->
            runCatching { BubbleVisibilityMode.valueOf(stored) }.getOrNull()
        } ?: DEFAULT_VISIBILITY
    }

    suspend fun setVisibilityMode(mode: BubbleVisibilityMode) {
        dataStore.edit { it[KEY_VISIBILITY] = mode.name }
    }

    /**
     * Last-known bubble position in absolute window coordinates. Returns
     * `(UNSET_COORDINATE, UNSET_COORDINATE)` when nothing has been persisted
     * yet — the overlay service interprets this as "use the default
     * placement" rather than "(0, 0)".
     */
    val position: Flow<BubblePosition> = dataStore.data.map { prefs ->
        BubblePosition(
            x = prefs[KEY_X] ?: UNSET_COORDINATE,
            y = prefs[KEY_Y] ?: UNSET_COORDINATE,
        )
    }

    suspend fun setPosition(x: Int, y: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_X] = x
            prefs[KEY_Y] = y
        }
    }
}

/**
 * Coordinate pair for the bubble overlay. [x] and [y] are window-absolute
 * pixels; the sentinel value [BubbleSettingsRepository.UNSET_COORDINATE]
 * means "no position has been persisted yet".
 */
data class BubblePosition(val x: Int, val y: Int) {
    val isUnset: Boolean
        get() = x == BubbleSettingsRepository.UNSET_COORDINATE ||
            y == BubbleSettingsRepository.UNSET_COORDINATE
}
