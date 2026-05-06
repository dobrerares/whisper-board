package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleCooldownTest {

    @Test
    fun `inactive when never dismissed`() {
        val c = BubbleCooldown(dismissedAt = null, cooldownMs = 5 * 60_000L)
        assertFalse(c.isActive(now = 1_000_000L))
    }

    @Test
    fun `active when within cooldown window`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertTrue(c.isActive(now = 3_000L))
    }

    @Test
    fun `inactive when cooldown elapsed`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertFalse(c.isActive(now = 6_000L))
    }

    @Test
    fun `inactive exactly at cooldown end`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 5_000L)
        assertFalse(c.isActive(now = 6_000L))
    }

    @Test
    fun `MAX_VALUE cooldown stays active for any reasonable now`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = Long.MAX_VALUE)
        assertTrue(c.isActive(now = 1_000_000_000L))
    }

    @Test
    fun `zero cooldown immediately inactive`() {
        val c = BubbleCooldown(dismissedAt = 1_000L, cooldownMs = 0L)
        assertFalse(c.isActive(now = 1_000L))
    }
}
