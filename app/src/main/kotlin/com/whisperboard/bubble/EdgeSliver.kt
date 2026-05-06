package com.whisperboard.bubble

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Visual state of the [EdgeSliver]. The bubble's [BubbleState] is mapped
 * to a SliverColor by the consumer (BubbleView). The mapping is:
 *
 * | BubbleState     | SliverColor       |
 * | --------------- | ----------------- |
 * | Idle            | IdleBlue          |
 * | Recording       | RecordingRed      |
 * | Processing      | ProcessingAmber   |
 * | Result          | ResultGreen       |
 * | Peeked          | IdleBlue          |
 * | Dismissed       | Hidden            |
 */
enum class SliverColor { IdleBlue, RecordingRed, ProcessingAmber, ResultGreen, Hidden }

/** Width of the visible sliver — see spec § Idle state. */
val SLIVER_WIDTH = 8.dp

/**
 * Vertical length of the sliver. ~48 dp matches a Material small FAB rail
 * tab — long enough to see, short enough to not dominate.
 */
val SLIVER_HEIGHT = 48.dp

/**
 * The 8 dp visible edge sliver. Touch handling is the caller's job — this
 * composable only renders. The caller (BubbleView, in a later task) wraps
 * it in a Modifier that adds the 24 dp invisible touch margin and the
 * gesture-detection pointerInput.
 *
 * When [color] is [SliverColor.Hidden] the composable renders nothing
 * (returns early). The service detaches the overlay window entirely
 * during cooldown, so this branch is defensive.
 */
@Composable
fun EdgeSliver(
    color: SliverColor,
    edge: Edge,
    modifier: Modifier = Modifier,
) {
    if (color == SliverColor.Hidden) return

    val pulse = if (color == SliverColor.RecordingRed) {
        val transition = rememberInfiniteTransition(label = "sliver-pulse")
        val s by transition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.10f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sliver-pulse-scale",
        )
        s
    } else 1f

    val tint = when (color) {
        SliverColor.IdleBlue -> Color(0x9938A3FF)
        SliverColor.RecordingRed -> Color(0xFFE53935)
        SliverColor.ProcessingAmber -> Color(0xFFFFB300)
        SliverColor.ResultGreen -> Color(0xFF43A047)
        SliverColor.Hidden -> Color.Transparent
    }

    val cornerShape = when (edge) {
        Edge.RIGHT -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
        Edge.LEFT -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    }

    Box(
        modifier = modifier
            .width(SLIVER_WIDTH)
            .height(SLIVER_HEIGHT)
            .scale(pulse)
            .clip(cornerShape)
            .background(tint),
    )
}
