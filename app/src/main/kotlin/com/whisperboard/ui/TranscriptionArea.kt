package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.history.DictationEntry

/**
 * The IME's transcript area. Two modes, picked by the IME based on whether
 * auto-insert is on AND retention is enabled:
 *
 * - **History scroll** (auto-insert ON + retention ON): renders a
 *   `LazyColumn` of recent dictation entries so the user can tap to
 *   re-insert, long-press for a context menu, or "show raw" inline. Per
 *   slice 6b's brief, this replaces the previously-cleared empty area.
 *
 * - **Single-utterance preview** (auto-insert OFF, or retention OFF):
 *   renders the current staged transcript with the same tap-to-commit
 *   behaviour the slice 6a flow already had. Empty staging shows the
 *   "Tap mic to start..." placeholder.
 *
 * Picking the mode at the call site (not inside this composable) keeps the
 * decision visible in `KeyboardScreen` and makes both branches unit-testable
 * in isolation. The shared 80dp height is preserved so the keyboard's total
 * vertical layout doesn't shift when the user toggles auto-insert.
 */
@Composable
fun TranscriptionArea(
    text: String,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    polishUnavailable: Boolean = false,
    historyEntries: List<DictationEntry> = emptyList(),
    historyEnabled: Boolean = false,
    onTapHistoryEntry: (DictationEntry) -> Unit = {},
    onDeleteHistoryEntry: (DictationEntry) -> Unit = {},
) {
    Surface(
        modifier = modifier.height(80.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        if (historyEnabled) {
            // History scroll — replaces the cleared empty area in the
            // auto-insert flow per slice 6b's brief.
            DictationHistoryView(
                entries = historyEntries,
                onTap = onTapHistoryEntry,
                onDelete = onDeleteHistoryEntry,
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                compactRows = true,
                emptyStateText = "Speak and your dictations appear here.",
            )
        } else {
            SingleUtterancePreview(
                text = text,
                onCommit = onCommit,
                polishUnavailable = polishUnavailable,
            )
        }
    }
}

@Composable
private fun SingleUtterancePreview(
    text: String,
    onCommit: () -> Unit,
    polishUnavailable: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = text.isNotEmpty()) { onCommit() }
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Tap mic to start...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (text.isNotEmpty() && polishUnavailable) {
            Text(
                text = "polish unavailable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
