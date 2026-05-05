package com.whisperboard.bubble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings for the bubble surface.
 *
 * Persisted shapes:
 * - **Visibility mode** — [BubbleVisibilityMode] enum with default
 *   [BubbleVisibilityMode.AlwaysVisible] per the brief.
 * - **Position** — `x`/`y` integers for the last-known overlay coordinates
 *   so the bubble re-appears where the user last left it. Persisted in the
 *   same DataStore so we don't multiply DataStore singletons.
 * - **Standalone use count** — increments on each completed standalone-mode
 *   utterance; gates the first-time accessibility nudge.
 * - **Accessibility nudge dismissed** — once set, the nudge never re-shows.
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
        private val KEY_STANDALONE_USE_COUNT = intPreferencesKey("bubble_standalone_use_count")
        private val KEY_NUDGE_DISMISSED = booleanPreferencesKey("bubble_accessibility_nudge_dismissed")

        val DEFAULT_VISIBILITY = BubbleVisibilityMode.AlwaysVisible

        /** Sentinel meaning "no position saved yet". */
        const val UNSET_COORDINATE: Int = Int.MIN_VALUE

        /**
         * Number of standalone-mode dictations the user must complete before
         * the in-place insertion nudge appears. Per the Agent Brief: nudge
         * "after they've already seen value before the permission ask",
         * suggested N = 3.
         */
        const val ACCESSIBILITY_NUDGE_THRESHOLD: Int = 3
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

    /**
     * Total number of completed standalone-mode utterances on this device.
     * Caps at [Int.MAX_VALUE]; counted only for utterances that produced
     * non-blank text so we don't reward false starts.
     */
    val standaloneUseCount: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_STANDALONE_USE_COUNT] ?: 0
    }

    suspend fun incrementStandaloneUseCount() {
        dataStore.edit { prefs ->
            val current = prefs[KEY_STANDALONE_USE_COUNT] ?: 0
            // Saturating increment — no real user will hit Int.MAX_VALUE
            // dictations, but the gate logic must not behave erratically if
            // they do.
            prefs[KEY_STANDALONE_USE_COUNT] = if (current == Int.MAX_VALUE) current else current + 1
        }
    }

    /**
     * Whether the user has dismissed the accessibility upgrade nudge. Once
     * `true`, the nudge never re-shows — the brief explicitly forbids
     * re-prompting after dismissal.
     */
    val accessibilityNudgeDismissed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NUDGE_DISMISSED] ?: false
    }

    suspend fun setAccessibilityNudgeDismissed(dismissed: Boolean) {
        dataStore.edit { it[KEY_NUDGE_DISMISSED] = dismissed }
    }
}

/**
 * Pure decision: should the in-place insertion nudge be shown right now?
 *
 * The brief: nudge after N standalone-mode uses, never after dismissal,
 * never when accessibility is already enabled. Splitting this out as a
 * top-level function keeps the rule JVM-testable without spinning up the
 * DataStore.
 */
fun shouldShowAccessibilityNudge(
    standaloneUseCount: Int,
    accessibilityNudgeDismissed: Boolean,
    accessibilityEnabled: Boolean,
    threshold: Int = BubbleSettingsRepository.ACCESSIBILITY_NUDGE_THRESHOLD,
): Boolean {
    if (accessibilityEnabled) return false
    if (accessibilityNudgeDismissed) return false
    return standaloneUseCount >= threshold
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
