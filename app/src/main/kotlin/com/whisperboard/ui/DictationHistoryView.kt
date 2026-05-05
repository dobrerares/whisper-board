package com.whisperboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whisperboard.model.history.DictationEntry
import java.text.DateFormat
import java.util.Date

/**
 * Vertical scroll of recent dictation entries. Two surfaces consume this:
 *
 * - The IME's transcript area (when auto-insert is on and retention is on),
 *   passing a small `recent(limit)` list and `compactRows = true` so each
 *   row is a one-line tap target.
 * - The Settings → History page, passing the unbounded `all()` list and
 *   `compactRows = false` so each row carries its full polished text and
 *   metadata.
 *
 * Per-row interactions:
 *
 * - **Tap** — re-insert the polished transcript (for the IME) or open the
 *   share sheet (for the Settings page; tapping in Settings has no focused
 *   field to insert into).
 * - **Long-press** — open a context menu with copy / delete / view raw /
 *   share. The "view raw" toggle expands the row inline; the rest fire
 *   straight at the callbacks.
 * - **"Show raw" toggle** — small chevron next to the polished text that
 *   reveals the unpolished transcript inline.
 *
 * Swipe-to-delete is intentionally implemented at the row level rather than
 * through Material3's `SwipeToDismiss` so the IME's compact row can remain
 * a tight tap target. Long-press → delete covers the same intent without
 * adding a swipe gesture that would conflict with the lazy column's
 * vertical scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DictationHistoryView(
    entries: List<DictationEntry>,
    onTap: (DictationEntry) -> Unit,
    onDelete: (DictationEntry) -> Unit,
    modifier: Modifier = Modifier,
    compactRows: Boolean = true,
    emptyStateText: String = "No dictation history yet.",
) {
    val context = LocalContext.current
    if (entries.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyStateText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            DictationHistoryRow(
                entry = entry,
                compact = compactRows,
                onTap = { onTap(entry) },
                onCopy = { copyToClipboard(context, entry.polishedTranscript) },
                onShare = { shareText(context, entry.polishedTranscript) },
                onDelete = { onDelete(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictationHistoryRow(
    entry: DictationEntry,
    compact: Boolean,
    onTap: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = { menuOpen = true },
            ),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.polishedTranscript,
                    style = if (compact) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (compact) 1 else Int.MAX_VALUE,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (showRaw) "hide raw" else "show raw",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .combinedClickable(onClick = { showRaw = !showRaw })
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Entry options",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (showRaw) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.rawTranscript,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!compact) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatMetadata(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    menuOpen = false
                    onCopy()
                },
            )
            DropdownMenuItem(
                text = { Text(if (showRaw) "Hide raw" else "View raw") },
                onClick = {
                    menuOpen = false
                    showRaw = !showRaw
                },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    menuOpen = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

private fun formatMetadata(entry: DictationEntry): String {
    val ts = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(entry.timestampMs))
    val parts = mutableListOf<String>()
    parts += ts
    if (entry.languageCodes.isNotEmpty()) {
        parts += entry.languageCodes.joinToString(",") { it.uppercase() }
    }
    entry.targetAppName?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Whisper Board", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
