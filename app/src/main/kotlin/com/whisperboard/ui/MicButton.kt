package com.whisperboard.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PTT_THRESHOLD_MS = 300L

@Composable
fun MicButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    onToggle: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var isPtt by remember { mutableStateOf(false) }

    val containerColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isProcessing -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val contentColor = when {
        isRecording -> MaterialTheme.colorScheme.onError
        isProcessing -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    // Pulse while recording in toggle mode
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    // PTT scale-up
    val pttScale by animateFloatAsState(
        targetValue = if (isPtt) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ptt-scale",
    )

    val scale = when {
        isPtt -> pttScale
        isRecording -> pulseScale
        else -> 1f
    }

    Box(
        modifier = modifier
            .size(80.dp)
            .scale(scale)
            .pointerInput(isRecording, isProcessing) {
                if (isProcessing) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    if (isRecording) {
                        // Already recording in toggle mode -- tap to stop
                        waitForUpOrCancellation()
                        onStopRecording()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else {
                        // Not recording -- detect short tap vs long press
                        var pttActivated = false
                        var timerJob: Job? = null
                        try {
                            timerJob = scope.launch {
                                delay(PTT_THRESHOLD_MS)
                                // Long press -> push-to-talk
                                pttActivated = true
                                isPtt = true
                                onStartRecording()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            waitForUpOrCancellation()
                        } finally {
                            timerJob?.cancel()
                        }
                        if (pttActivated) {
                            // Release after PTT
                            isPtt = false
                            onStopRecording()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else {
                            // Short tap -> toggle
                            onToggle()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        FloatingActionButton(
            onClick = {}, // handled by pointerInput
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
            ),
        ) {
            when {
                isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = contentColor,
                        strokeWidth = 3.dp,
                    )
                }
                else -> {
                    Icon(
                        painter = painterResource(
                            id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                        ),
                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}
