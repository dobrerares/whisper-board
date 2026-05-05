package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [shouldShowAccessibilityNudge] — the pure rule deciding when the
 * first-time in-place insertion nudge appears in Settings -> Bubble.
 *
 * Per the Agent Brief and ADR-0001:
 *
 * - Nudge appears once standalone-mode use crosses the threshold (default 3).
 * - Nudge never appears once dismissed.
 * - Nudge never appears once accessibility is granted.
 * - The thresholds default lives on [BubbleSettingsRepository] but the
 *   function accepts an override so tests don't have to assume the value.
 *
 * Splitting this out as a function (rather than an `if` in the composable)
 * keeps the gating rule testable in isolation; the composable just
 * collects the flows and asks the function.
 */
class AccessibilityNudgeRulesTest {

    @Test
    fun `nudge does not show below threshold`() {
        for (count in 0 until BubbleSettingsRepository.ACCESSIBILITY_NUDGE_THRESHOLD) {
            assertFalse(
                "Standalone use count $count is below threshold; nudge must hide",
                shouldShowAccessibilityNudge(
                    standaloneUseCount = count,
                    accessibilityNudgeDismissed = false,
                    accessibilityEnabled = false,
                ),
            )
        }
    }

    @Test
    fun `nudge shows once threshold is reached`() {
        assertTrue(
            shouldShowAccessibilityNudge(
                standaloneUseCount = BubbleSettingsRepository.ACCESSIBILITY_NUDGE_THRESHOLD,
                accessibilityNudgeDismissed = false,
                accessibilityEnabled = false,
            ),
        )
    }

    @Test
    fun `nudge stays visible past threshold while not dismissed`() {
        assertTrue(
            shouldShowAccessibilityNudge(
                standaloneUseCount = BubbleSettingsRepository.ACCESSIBILITY_NUDGE_THRESHOLD + 50,
                accessibilityNudgeDismissed = false,
                accessibilityEnabled = false,
            ),
        )
    }

    @Test
    fun `nudge hides forever once dismissed regardless of count`() {
        assertFalse(
            shouldShowAccessibilityNudge(
                standaloneUseCount = 100,
                accessibilityNudgeDismissed = true,
                accessibilityEnabled = false,
            ),
        )
    }

    @Test
    fun `nudge hides when accessibility is already enabled`() {
        // Even past threshold and undismissed, no nudge once granted —
        // the prompt has done its job.
        assertFalse(
            shouldShowAccessibilityNudge(
                standaloneUseCount = 100,
                accessibilityNudgeDismissed = false,
                accessibilityEnabled = true,
            ),
        )
    }

    @Test
    fun `enabled trumps dismissed and count`() {
        assertFalse(
            shouldShowAccessibilityNudge(
                standaloneUseCount = 0,
                accessibilityNudgeDismissed = true,
                accessibilityEnabled = true,
            ),
        )
    }

    @Test
    fun `custom threshold is honoured`() {
        // Make sure the function accepts a runtime override — useful when
        // experimenting with N=5, N=1, etc.
        assertFalse(
            shouldShowAccessibilityNudge(
                standaloneUseCount = 4,
                accessibilityNudgeDismissed = false,
                accessibilityEnabled = false,
                threshold = 5,
            ),
        )
        assertTrue(
            shouldShowAccessibilityNudge(
                standaloneUseCount = 5,
                accessibilityNudgeDismissed = false,
                accessibilityEnabled = false,
                threshold = 5,
            ),
        )
    }
}
