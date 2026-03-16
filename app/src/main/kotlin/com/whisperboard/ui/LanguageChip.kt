package com.whisperboard.ui

import androidx.compose.material3.AssistChip
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
) {
    var showPicker by remember { mutableStateOf(false) }

    val displayText = if (language == "auto") {
        "Auto-detect"
    } else {
        WhisperLanguages.displayName(language)
    }

    AssistChip(
        onClick = { showPicker = true },
        label = {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium
            )
        },
        modifier = modifier
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
