package com.whisperboard.bubble

/**
 * Which screen edge the bubble sliver lives on. Persisted in
 * [BubbleSettingsRepository]; consumed by the overlay service to anchor
 * the window and by [DragTear] to interpret gesture direction.
 */
enum class Edge { LEFT, RIGHT }

/**
 * Pure gesture classifier. The user dismisses the sliver by dragging it
 * perpendicular to the edge (away from the edge, across the screen). If
 * the perpendicular travel passes [DISMISS_FRACTION] of the screen width,
 * the gesture is a dismiss.
 *
 * The function is intentionally framework-free: only Float / Int / enum
 * inputs, so it runs as a JVM unit test.
 */
object DragTear {

    /**
     * Threshold for "dragged far enough to dismiss". 30% of screen width
     * is high enough to avoid accidental dismisses but low enough to feel
     * responsive on a 360 dp phone (~108 dp on 1080-px width).
     */
    const val DISMISS_FRACTION: Float = 0.30f

    fun isDismissAttempt(
        startX: Float,
        currentX: Float,
        screenWidth: Int,
        edge: Edge,
    ): Boolean {
        if (screenWidth <= 0) return false
        val travel = when (edge) {
            Edge.RIGHT -> startX - currentX
            Edge.LEFT -> currentX - startX
        }
        if (travel <= 0f) return false
        return travel >= screenWidth * DISMISS_FRACTION
    }
}
