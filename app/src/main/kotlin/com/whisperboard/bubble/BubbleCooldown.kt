package com.whisperboard.bubble

/**
 * Pure cooldown calculation. The bubble is hidden for [cooldownMs] after
 * [dismissedAt], then auto-returns. Lives in its own file so its tests
 * don't pull in the rest of the bubble package.
 *
 * - [dismissedAt] = null means never dismissed; cooldown is never active.
 * - [cooldownMs] == Long.MAX_VALUE means "Until next time the user opens
 *   the app" — always active until external code clears [dismissedAt].
 *
 * The "now" parameter is injected so tests don't need a clock mock; the
 * service passes [System.currentTimeMillis] in production.
 */
data class BubbleCooldown(
    val dismissedAt: Long?,
    val cooldownMs: Long,
) {
    fun isActive(now: Long): Boolean {
        val start = dismissedAt ?: return false
        if (cooldownMs == Long.MAX_VALUE) return true
        return now < start + cooldownMs
    }
}
