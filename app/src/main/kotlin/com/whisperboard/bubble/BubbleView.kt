package com.whisperboard.bubble

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Long-press threshold (ms) — matches the existing IME PTT threshold. */
private const val PTT_THRESHOLD_MS = 200L

/** Auto-close peek sheet after this long with no interaction. */
private const val PEEK_TIMEOUT_MS = 4_000L

/** Invisible touch-margin around the visible sliver — gesture target. */
private val SLIVER_TOUCH_MARGIN = 24.dp

/**
 * Top-level Compose root for the bubble overlay window. Decides whether
 * to render [EdgeSliver] alone, [SummonedSheet] anchored next to it, or
 * neither (when in [BubbleState.Dismissed] cooldown — but the service
 * detaches the window entirely in that case, so this branch is defensive).
 *
 * Touch handling: a [SLIVER_TOUCH_MARGIN]-wide invisible band along the
 * edge is the gesture detection zone. Tap, long-press, drag-perpendicular,
 * and drag-along-edge are all detected here; classification of "is this
 * drag a dismiss-tear?" is the service's job (it knows the screen width
 * and uses [DragTear.isDismissAttempt]).
 */
@Composable
fun BubbleView(
    state: BubbleState,
    edge: Edge,
    lastResultText: String?,
    onTap: () -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onDragHorizontal: (startX: Float, currentX: Float) -> Unit,
    onDragVertical: (dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onPeekTimeout: () -> Unit,
    onReinsert: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliverColor = when (state) {
        is BubbleState.Idle -> SliverColor.IdleBlue
        is BubbleState.Recording -> SliverColor.RecordingRed
        is BubbleState.Processing -> SliverColor.ProcessingAmber
        is BubbleState.Result -> SliverColor.ResultGreen
        is BubbleState.Peeked -> SliverColor.IdleBlue
        is BubbleState.Dismissed -> SliverColor.Hidden
    }

    val sheetVisible = state is BubbleState.Peeked ||
        state is BubbleState.Recording ||
        state is BubbleState.Processing ||
        state is BubbleState.Result

    if (state is BubbleState.Peeked) {
        LaunchedEffect(Unit) {
            delay(PEEK_TIMEOUT_MS)
            onPeekTimeout()
        }
    }

    val sliverWithGestures: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .padding(
                    start = if (edge == Edge.RIGHT) SLIVER_TOUCH_MARGIN else 0.dp,
                    end = if (edge == Edge.LEFT) SLIVER_TOUCH_MARGIN else 0.dp,
                )
                .pointerInput(state, edge) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val downAt = System.currentTimeMillis()
                        val up = waitForUpOrCancellation()
                        val elapsed = System.currentTimeMillis() - downAt
                        if (up != null) {
                            if (elapsed >= PTT_THRESHOLD_MS) {
                                onLongPressStart()
                                onLongPressEnd()
                            } else {
                                onTap()
                            }
                        }
                    }
                }
                .pointerInput(state, edge) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onDragHorizontal(offset.x, offset.x)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragHorizontal(
                                change.previousPosition.x,
                                change.position.x,
                            )
                            onDragVertical(dragAmount.y)
                        },
                    )
                },
        ) {
            EdgeSliver(color = sliverColor, edge = edge)
        }
    }

    val sheetIfVisible: @Composable () -> Unit = {
        if (sheetVisible) {
            SummonedSheet(
                lastResultText = lastResultText,
                edge = edge,
                onMicLongPress = onLongPressStart,
                onReinsert = onReinsert,
                onCopy = onCopy,
            )
        }
    }

    Box(modifier = modifier) {
        when (edge) {
            Edge.RIGHT -> Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sheetIfVisible()
                sliverWithGestures()
            }
            Edge.LEFT -> Row(
                modifier = Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sliverWithGestures()
                sheetIfVisible()
            }
        }
    }
}
