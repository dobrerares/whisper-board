package com.whisperboard.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperboard.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditingKeysRow(
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RepeatableEditKey(
            onAction = { onAction(EditAction.Backspace) },
            repeatDelayMs = 400L,
            repeatIntervalMs = 50L,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_backspace),
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        EditKey(onClick = { onAction(EditAction.Comma) }) {
            Text(",", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Space) }, weight = 3f) {
            Text("space", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Period) }) {
            Text(".", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Enter) }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_enter),
                contentDescription = "Enter",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RowScope.RepeatableEditKey(
    onAction: () -> Unit,
    repeatDelayMs: Long,
    repeatIntervalMs: Long,
    weight: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .padding(horizontal = 2.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onAction()
                    var repeatJob: Job? = null
                    try {
                        repeatJob = scope.launch {
                            delay(repeatDelayMs)
                            while (true) {
                                onAction()
                                delay(repeatIntervalMs)
                            }
                        }
                        waitForUpOrCancellation()
                    } finally {
                        repeatJob?.cancel()
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun RowScope.EditKey(
    onClick: () -> Unit,
    weight: Float = 1f,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
