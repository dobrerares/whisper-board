package com.whisperboard.ui

import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
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
import com.whisperboard.ui.theme.WhisperBoardTheme

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
    val polishUnavailable by viewModel.polishUnavailable.collectAsState()

    WhisperBoardTheme {
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            viewModel.errorMessage.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }

        // Auto-insert path: when the view-model emits a finished transcript
        // and auto-insert is on, write it directly into the focused field via
        // `InputConnection.commitText`. This is the slice 6a default. When
        // auto-insert is off the view-model stages the transcript in
        // `transcribedText` instead, and the user taps `TranscriptionArea` to
        // commit. (Slice 6b/issue #8 will replace the staged-transcript area
        // with a dictation-history scroll.)
        LaunchedEffect(Unit) {
            viewModel.autoInsertRequests.collect { text ->
                inputConnection()?.commitText(text, 1)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
                    modifier = Modifier.fillMaxWidth(),
                    polishUnavailable = polishUnavailable,
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
                    onStartRecording = { viewModel.startRecording() },
                    onStopRecording = { viewModel.stopRecording() },
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
