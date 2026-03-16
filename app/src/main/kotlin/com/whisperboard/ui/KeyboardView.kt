package com.whisperboard.ui

import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun KeyboardScreen(
    viewModel: KeyboardViewModel,
    inputConnection: () -> InputConnection?
) {
    val transcribedText by viewModel.transcribedText.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val activeLanguage by viewModel.activeLanguage.collectAsState()
    val favoriteLanguages by viewModel.favoriteLanguages.collectAsState()

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TranscriptionArea(
                text = transcribedText,
                onCommit = { viewModel.commitText(inputConnection()) },
                modifier = Modifier.fillMaxWidth()
            )

            LanguageChip(
                language = activeLanguage,
                favorites = favoriteLanguages,
                onSelectLanguage = { viewModel.setLanguage(it) },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )

            MicButton(
                isRecording = isRecording,
                isProcessing = isProcessing,
                onToggle = { viewModel.toggleRecording() },
                modifier = Modifier.padding(vertical = 8.dp)
            )

            EditingKeysRow(
                onAction = { action -> viewModel.onEditAction(action, inputConnection()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
