package com.whisperboard.bubble

/**
 * The user-facing visibility setting for the bubble. Persisted in
 * [BubbleSettingsRepository]; consumed by [BubbleVisibilityRules] to decide
 * whether the overlay should be on screen.
 */
enum class BubbleVisibilityMode {
    /**
     * Default. The bubble is always on screen unless an automatic rule
     * (lockscreen, fullscreen app, off-edge drag) hides it.
     */
    AlwaysVisible,

    /**
     * The bubble appears only after explicit summon — Quick Settings tile
     * tap or in-app "show bubble" action.
     */
    SummonedOnly,

    /** The bubble service is not running. */
    Disabled,
}

/**
 * Snapshot of the device + user state inputs that decide whether the bubble
 * should currently be visible. Built by the overlay service from system
 * callbacks; passed to [BubbleVisibilityRules.shouldBeVisible] which is the
 * only place visibility rules live.
 *
 * Note: [imeVisible] is captured *only* to assert it does not influence the
 * visibility decision — see ADR-0001 and the brief's anti-regression test.
 * The bubble and any keyboard remain functional in parallel.
 */
data class BubbleVisibilityInputs(
    /** User setting: `AlwaysVisible`, `SummonedOnly`, or `Disabled`. */
    val mode: BubbleVisibilityMode,
    /** True iff the device is on the lockscreen / keyguard. */
    val onLockscreen: Boolean,
    /**
     * True iff the foreground app declared `FLAG_FULLSCREEN` /
     * `SYSTEM_UI_FLAG_FULLSCREEN`. The brief calls this case out for video
     * players, games, etc.
     */
    val foregroundAppIsFullscreen: Boolean,
    /**
     * True iff the user explicitly dragged the bubble off the visible window
     * area. Re-summon via the Quick Settings tile or settings is the way back.
     */
    val draggedOffEdge: Boolean,
    /**
     * True iff an IME is currently visible. The bubble must remain on screen
     * regardless — the brief explicitly forbids this from causing a hide.
     */
    val imeVisible: Boolean,
    /**
     * `SummonedOnly` users open the bubble through a summon action; while
     * summoned the bubble is on, otherwise it is off. `AlwaysVisible` users
     * ignore this flag.
     */
    val isSummoned: Boolean = false,
    /** True iff the dismiss cooldown is currently active.
     *  See [BubbleCooldown] for the calculation. */
    val cooldownActive: Boolean = false,
)

/**
 * Pure visibility decision. The brief calls this out as a *deep* function:
 * the rule set is small but the regression risk (especially around the
 * IME-up case) is high enough to warrant a dedicated, testable home.
 *
 * Rules, in order:
 * 1. [BubbleVisibilityMode.Disabled] -> hidden, full stop.
 * 2. Cooldown active -> hidden until cooldown expires.
 * 3. Lockscreen -> hidden.
 * 4. Foreground app declared fullscreen -> hidden.
 * 5. User dragged off-edge -> hidden until re-summoned.
 * 6. [BubbleVisibilityMode.SummonedOnly] -> visible iff `isSummoned`.
 * 7. [BubbleVisibilityMode.AlwaysVisible] -> visible.
 *
 * IME visibility is intentionally absent from the rule set. ADR-0001 declares
 * the bubble and the IME independent surfaces.
 */
object BubbleVisibilityRules {
    fun shouldBeVisible(inputs: BubbleVisibilityInputs): Boolean {
        if (inputs.mode == BubbleVisibilityMode.Disabled) return false
        if (inputs.cooldownActive) return false
        if (inputs.onLockscreen) return false
        if (inputs.foregroundAppIsFullscreen) return false
        if (inputs.draggedOffEdge) return false
        return when (inputs.mode) {
            BubbleVisibilityMode.AlwaysVisible -> true
            BubbleVisibilityMode.SummonedOnly -> inputs.isSummoned
            BubbleVisibilityMode.Disabled -> false // unreachable — handled above
        }
    }
}
