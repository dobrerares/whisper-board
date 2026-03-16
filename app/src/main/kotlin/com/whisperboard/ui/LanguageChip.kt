package com.whisperboard.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LanguageChip(
    language: String,
    modifier: Modifier = Modifier
) {
    val displayText = if (language == "auto") "Auto-detect" else language.uppercase()

    AssistChip(
        onClick = { /* Language picker will come later */ },
        label = {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelMedium
            )
        },
        modifier = modifier
    )
}
