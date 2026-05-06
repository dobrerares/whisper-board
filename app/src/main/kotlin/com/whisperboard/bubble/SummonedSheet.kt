package com.whisperboard.bubble

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whisperboard.R

/**
 * The action sheet that slides out when the user taps the sliver.
 * Anchored visually to the sliver's edge by [edge]. Contains:
 *  - a mic button (32 dp); long-press to record from inside the sheet;
 *  - a one-line preview of the most recent dictation entry, plus
 *    re-insert and copy affordances.
 *
 * The sheet's overall width is bounded so it never takes more than ~50%
 * of a 360 dp phone width.
 *
 * v1 of the redesign uses `R.drawable.ic_mic` for all three icon buttons
 * (mic / re-insert / copy) because that's the only relevant drawable in
 * the project today. Text labels disambiguate; follow-up issue can add
 * proper ic_redo / ic_copy drawables.
 */
@Composable
fun SummonedSheet(
    lastResultText: String?,
    edge: Edge,
    onMicLongPress: () -> Unit,
    onReinsert: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetCorners = when (edge) {
        Edge.RIGHT -> RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
        Edge.LEFT -> RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
    }

    Surface(
        modifier = modifier.widthIn(min = 160.dp, max = 220.dp),
        shape = sheetCorners,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "Long-press to record",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Hold to dictate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }

            if (lastResultText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastResultText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onReinsert) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "Re-insert into focused field",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onCopy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = "Copy to clipboard",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
