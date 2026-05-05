package com.whisperboard.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.WhisperLanguages

/**
 * One-screen first-launch prompt. The user multi-selects the languages they
 * speak; this becomes the **language profile** consumed by the post-processor.
 *
 * Two exits:
 *
 * - **Save** — emits [onSave] with the selected codes. Empty selection
 *   collapses to `[auto]` in the repository.
 * - **Skip** — emits [onSkip], which the caller persists as the default
 *   `[auto]` profile and marks onboarding complete.
 *
 * Both exits flip the `onboardingComplete` flag; the prompt never re-appears.
 * Skippable per CONTEXT.md — skipping preserves the existing zero-context
 * behaviour and is a deliberate first-class option, not a corner case.
 *
 * The composable is purposely passive about *when* to show — the caller
 * (`SettingsActivity`) reads `LanguageRepository.onboardingComplete` and
 * gates the screen on it. This keeps the prompt testable in isolation and
 * doesn't bake first-launch detection into the screen itself.
 */
@Composable
fun FirstLaunchPrompt(
    initialSelection: Set<String> = emptySet(),
    onSkip: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    // We model selection as a SnapshotStateList of ISO codes; it survives
    // configuration changes via rememberSaveable's listSaver.
    val selected = rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) {
        initialSelection.filter { it != "auto" }.toMutableStateList()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Which languages do you speak?",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Whisper Board uses this to recover code-switched phrases " +
                        "in your transcripts. It is not passed to speech-to-text — " +
                        "Whisper still auto-detects per utterance.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                items(
                    WhisperLanguages.codes.entries.toList(),
                    key = { "onboard-lang-${it.key}" },
                ) { (code, name) ->
                    LanguageCheckRow(
                        code = code,
                        displayName = name,
                        checked = code in selected,
                        onToggle = { checked ->
                            if (checked) {
                                if (code !in selected) selected.add(code)
                            } else {
                                selected.remove(code)
                            }
                        },
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // "Skip" gets equal billing with "Save" per the brief —
                // multilingual users gain a lot, monolingual / casual users
                // shouldn't feel forced to fill out a form on first launch.
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }
                Button(onClick = { onSave(selected.toSet()) }) {
                    Text(
                        if (selected.isEmpty()) "Continue" else "Save (${selected.size})",
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageCheckRow(
    code: String,
    displayName: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle(it) },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = code.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
