package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bubble visibility rule set is pure but high-risk: the rule the brief
 * calls out *most explicitly* is "the IME being up must not hide the bubble"
 * — the bubble and the IME are independent surfaces per ADR-0001 and the
 * regression would gut the speak-anywhere story. That case has a dedicated
 * test below; the rest of the table covers each hide-trigger in isolation.
 */
class BubbleVisibilityRulesTest {

    private fun visible(
        mode: BubbleVisibilityMode = BubbleVisibilityMode.AlwaysVisible,
        onLockscreen: Boolean = false,
        foregroundAppIsFullscreen: Boolean = false,
        draggedOffEdge: Boolean = false,
        imeVisible: Boolean = false,
        isSummoned: Boolean = false,
    ): Boolean = BubbleVisibilityRules.shouldBeVisible(
        BubbleVisibilityInputs(
            mode = mode,
            onLockscreen = onLockscreen,
            foregroundAppIsFullscreen = foregroundAppIsFullscreen,
            draggedOffEdge = draggedOffEdge,
            imeVisible = imeVisible,
            isSummoned = isSummoned,
        )
    )

    // --- Mode-level rules ---

    @Test
    fun `disabled mode is always hidden regardless of other inputs`() {
        // Even with every "show" flag set, Disabled wins.
        assertFalse(
            visible(
                mode = BubbleVisibilityMode.Disabled,
                isSummoned = true,
            )
        )
    }

    @Test
    fun `always visible mode shows the bubble in the empty case`() {
        assertTrue(visible(mode = BubbleVisibilityMode.AlwaysVisible))
    }

    @Test
    fun `summoned-only mode hides until summoned`() {
        assertFalse(
            visible(
                mode = BubbleVisibilityMode.SummonedOnly,
                isSummoned = false,
            )
        )
        assertTrue(
            visible(
                mode = BubbleVisibilityMode.SummonedOnly,
                isSummoned = true,
            )
        )
    }

    // --- Hide triggers in always-visible mode ---

    @Test
    fun `lockscreen hides the bubble`() {
        assertFalse(visible(onLockscreen = true))
    }

    @Test
    fun `fullscreen foreground app hides the bubble`() {
        assertFalse(visible(foregroundAppIsFullscreen = true))
    }

    @Test
    fun `dragged off-edge hides the bubble`() {
        assertFalse(visible(draggedOffEdge = true))
    }

    // --- The "IME up does not hide the bubble" anti-regression ---

    @Test
    fun `IME visible does NOT hide the bubble in always-visible mode`() {
        // ADR-0001: bubble and IME are independent surfaces. The brief
        // explicitly calls this out as the rule to never break.
        assertTrue(
            "IME visibility must not be a hide-trigger — the bubble and " +
                "any keyboard remain functional in parallel.",
            visible(imeVisible = true),
        )
    }

    @Test
    fun `IME visible does NOT hide the bubble in summoned-only mode`() {
        assertTrue(
            "Even when summoned, an IME being up must not retroactively " +
                "hide the bubble.",
            visible(
                mode = BubbleVisibilityMode.SummonedOnly,
                isSummoned = true,
                imeVisible = true,
            )
        )
    }

    @Test
    fun `IME visible plus lockscreen still resolves to hidden because of lockscreen`() {
        // Confirms that the IME flag is not the cause of the hide — lockscreen is.
        // If we ever flip the rule by mistake, this test still passes (so we
        // pair it with the test above which is the real anti-regression).
        assertFalse(visible(onLockscreen = true, imeVisible = true))
    }

    // --- Hide-trigger precedence ---

    @Test
    fun `dragged off-edge wins over summoned-only summoned state`() {
        assertFalse(
            visible(
                mode = BubbleVisibilityMode.SummonedOnly,
                isSummoned = true,
                draggedOffEdge = true,
            )
        )
    }

    @Test
    fun `fullscreen wins even when always-visible`() {
        assertFalse(
            visible(
                mode = BubbleVisibilityMode.AlwaysVisible,
                foregroundAppIsFullscreen = true,
            )
        )
    }

    @Test
    fun `lockscreen wins even when always-visible`() {
        assertFalse(
            visible(
                mode = BubbleVisibilityMode.AlwaysVisible,
                onLockscreen = true,
            )
        )
    }

    @Test
    fun `cooldown active hides AlwaysVisible`() {
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.AlwaysVisible,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = true,
            )
        )
        assertFalse(visible)
    }

    @Test
    fun `cooldown active does not override Disabled`() {
        // Disabled is checked first; cooldown evaluation is moot.
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.Disabled,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = true,
            )
        )
        assertFalse(visible)
    }

    @Test
    fun `cooldown inactive plus AlwaysVisible is visible`() {
        val visible = BubbleVisibilityRules.shouldBeVisible(
            BubbleVisibilityInputs(
                mode = BubbleVisibilityMode.AlwaysVisible,
                onLockscreen = false,
                foregroundAppIsFullscreen = false,
                draggedOffEdge = false,
                imeVisible = false,
                isSummoned = false,
                cooldownActive = false,
            )
        )
        assertTrue(visible)
    }
}
