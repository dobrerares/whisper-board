package com.whisperboard.ui

import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val waveformData by viewModel.waveformData.collectAsState()

    MaterialTheme {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            viewModel.errorMessage.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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

                WaveformBar(
                    waveformData = waveformData,
                    isRecording = isRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )

                EditingKeysRow(
                    onAction = { action -> viewModel.onEditAction(action, inputConnection()) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
