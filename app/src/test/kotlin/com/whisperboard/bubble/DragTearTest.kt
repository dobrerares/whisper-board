package com.whisperboard.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DragTearTest {

    @Test
    fun `right-edge drag past 30 percent inward is a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f - (1080f * 0.31f),
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertTrue(classified)
    }

    @Test
    fun `right-edge drag of 29 percent is not a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f - (1080f * 0.29f),
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertFalse(classified)
    }

    @Test
    fun `right-edge drag in the wrong direction is not a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 1080f,
            currentX = 1080f + 200f,
            screenWidth = 1080,
            edge = Edge.RIGHT,
        )
        assertFalse(classified)
    }

    @Test
    fun `left-edge drag past 30 percent inward is a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = 1080f * 0.31f,
            screenWidth = 1080,
            edge = Edge.LEFT,
        )
        assertTrue(classified)
    }

    @Test
    fun `left-edge drag in the wrong direction is not a dismiss`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = -200f,
            screenWidth = 1080,
            edge = Edge.LEFT,
        )
        assertFalse(classified)
    }

    @Test
    fun `zero or negative screenWidth never classifies`() {
        val classified = DragTear.isDismissAttempt(
            startX = 0f,
            currentX = 100f,
            screenWidth = 0,
            edge = Edge.LEFT,
        )
        assertFalse(classified)
    }
}
