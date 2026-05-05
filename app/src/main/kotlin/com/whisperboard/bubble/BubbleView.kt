package com.whisperboard.bubble

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R

/**
 * Threshold (ms) above which a press becomes push-to-talk. Mirrors the
 * IME mic button's [com.whisperboard.ui.MicButton] threshold so users see
 * one consistent gesture grammar across both surfaces.
 */
private const val PTT_THRESHOLD_MS = 300L

/**
 * The collapsed dot diameter when in [BubbleState.Idle], [BubbleState.Recording],
 * or [BubbleState.Processing]. The expanded result state grows wider and
 * shows the polished transcript.
 */
private val BUBBLE_SIZE = 64.dp

/**
 * Compose UI for the bubble overlay. Renders the four [BubbleState] values
 * with distinct visuals (idle dot / recording with waveform / processing
 * spinner / expanded result), forwards gestures into the
 * [BubbleStateMachine], and reports drag events so the service can persist
 * the new position.
 *
 * The composable is intentionally lean: no animation polish beyond the
 * recording pulse and the PTT scale-up; reasonable defaults consistent with
 * `WhisperBoardTheme` per the brief's "out of scope" list.
 *
 * Drag gestures are reported as **window-relative deltas** (dx, dy) in
 * pixels — the overlay service translates those into absolute layout
 * params updates. The bubble composable itself stays unaware of
 * `WindowManager`.
 */
@Composable
fun BubbleView(
    state: BubbleState,
    waveformAmplitude: Float,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        BubbleState.Idle -> CollapsedBubble(
            tint = MaterialTheme.colorScheme.primary,
            content = {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = "Start dictation",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            },
            onTap = onTap,
            onLongPressStart = onLongPressStart,
            onLongPressEnd = onLongPressEnd,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = modifier,
        )
        is BubbleState.Recording -> CollapsedBubble(
            tint = MaterialTheme.colorScheme.error,
            content = {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = "Stop dictation",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    BubbleAmplitudePulse(amplitude = waveformAmplitude)
                }
            },
            onTap = onTap,
            onLongPressStart = onLongPressStart,
            onLongPressEnd = onLongPressEnd,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = modifier,
            recording = true,
        )
        BubbleState.Processing -> CollapsedBubble(
            tint = MaterialTheme.colorScheme.surfaceVariant,
            content = {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 3.dp,
                )
            },
            // While processing, gestures are absorbed by the state machine
            // (it returns the same state with no effects), but we still
            // forward them — the machine is the source of truth, not this
            // composable.
            onTap = onTap,
            onLongPressStart = onLongPressStart,
            onLongPressEnd = onLongPressEnd,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = modifier,
        )
        is BubbleState.Result -> ResultBubble(
            text = state.text,
            onTap = onDismiss,
            onLongPressStart = onLongPressStart,
            onLongPressEnd = onLongPressEnd,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            modifier = modifier,
        )
    }
}

@Composable
private fun CollapsedBubble(
    tint: Color,
    content: @Composable () -> Unit,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
) {
    val pulseScale = if (recording) {
        val transition = rememberInfiniteTransition(label = "bubble-pulse")
        val s by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bubble-pulse-scale",
        )
        s
    } else 1f

    Surface(
        modifier = modifier
            .size(BUBBLE_SIZE)
            .scale(pulseScale)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            }
            .pointerInput(Unit) {
                // Tap / long-press detector. We separate this from
                // detectDragGestures so the overlay service can move the
                // window during a drag without losing the long-press
                // semantics — drag wins when the finger actually moves,
                // long-press wins when it stays.
                awaitEachGesture {
                    awaitFirstDown()
                    val down = System.currentTimeMillis()
                    val up = waitForUpOrCancellation()
                    val elapsed = System.currentTimeMillis() - down
                    if (up != null) {
                        if (elapsed >= PTT_THRESHOLD_MS) {
                            // Long press — start, then immediately end on
                            // release. The state machine treats this as
                            // PTT.
                            onLongPressStart()
                            onLongPressEnd()
                        } else {
                            onTap()
                        }
                    }
                }
            },
        shape = CircleShape,
        color = tint,
    ) {
        Box(
            modifier = Modifier.size(BUBBLE_SIZE),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
private fun BubbleAmplitudePulse(amplitude: Float) {
    val ringSize = (BUBBLE_SIZE.value * (1f + amplitude * 0.3f)).dp
    Box(
        modifier = Modifier
            .size(ringSize)
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                shape = CircleShape,
            )
    )
}

@Composable
private fun ResultBubble(
    text: String,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(min = BUBBLE_SIZE, max = 320.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val down = System.currentTimeMillis()
                    val up = waitForUpOrCancellation()
                    val elapsed = System.currentTimeMillis() - down
                    if (up != null) {
                        if (elapsed >= PTT_THRESHOLD_MS) {
                            onLongPressStart()
                            onLongPressEnd()
                        } else {
                            onTap()
                        }
                    }
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Copied",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
