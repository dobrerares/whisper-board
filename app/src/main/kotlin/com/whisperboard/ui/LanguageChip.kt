package com.whisperboard.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.whisperboard.model.WhisperLanguages

@Composable
fun LanguageChip(
    language: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * When non-null, the chip flashes this language for ~1.5s after each
     * utterance to give the user a "we just heard X" cue, then reverts to
     * the persistent [language] selection. The persistent selection itself
     * is unchanged — flashing is purely visual feedback.
     */
    detectedFlash: String? = null,
) {
    var showPicker by remember { mutableStateOf(false) }

    // Prefer the transient flash over the persistent selection so the user
    // sees the detection result. When the flash subsides we drop back to
    // whatever the chip was on (auto by default).
    val displayLanguage = detectedFlash ?: language
    val displayText = if (displayLanguage == "auto") {
        "Auto-detect"
    } else {
        WhisperLanguages.displayName(displayLanguage)
    }

    FilterChip(
        selected = displayLanguage != "auto",
        onClick = { showPicker = true },
        label = {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.outline,
            enabled = true,
            selected = displayLanguage != "auto",
        ),
    )

    if (showPicker) {
        LanguagePickerDialog(
            activeLanguage = language,
            favorites = favorites,
            onSelectLanguage = { code ->
                onSelectLanguage(code)
                showPicker = false
            },
            onToggleFavorite = onToggleFavorite,
            onDismiss = { showPicker = false },
        )
    }
}
