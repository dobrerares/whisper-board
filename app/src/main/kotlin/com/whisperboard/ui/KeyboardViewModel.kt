package com.whisperboard.ui

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.whisper.WhisperContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class EditAction {
    data object Backspace : EditAction()
    data object Comma : EditAction()
    data object Space : EditAction()
    data object Period : EditAction()
    data object Enter : EditAction()
}

class KeyboardViewModel(
    private val audioPipeline: AudioPipeline,
) : ViewModel() {

    companion object {
        private const val TAG = "KeyboardViewModel"
    }

    @Volatile
    private var whisperContext: WhisperContext? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _activeLanguage = MutableStateFlow("auto")
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    val waveformData: StateFlow<FloatArray> = audioPipeline.waveformData

    var currentImeAction: Int = EditorInfo.IME_ACTION_DONE

    fun setWhisperContext(context: WhisperContext?) {
        whisperContext = context
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            // Stop recording and transcribe
            _isRecording.value = false
            viewModelScope.launch {
                try {
                    val samples = audioPipeline.stopRecording()
                    if (samples.isEmpty()) {
                        Log.w(TAG, "No audio samples captured")
                        return@launch
                    }

                    val ctx = whisperContext
                    if (ctx == null) {
                        _transcribedText.value = "[No model loaded]"
                        Log.w(TAG, "WhisperContext is null — no model loaded")
                        return@launch
                    }

                    _isProcessing.value = true
                    val segments = ctx.transcribe(samples, _activeLanguage.value)
                    _transcribedText.value = segments.joinToString(" ") { it.text.trim() }
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription failed", e)
                    _transcribedText.value = "[Transcription error]"
                } finally {
                    _isProcessing.value = false
                }
            }
        } else {
            // Start recording
            val ctx = whisperContext
            if (ctx == null) {
                _transcribedText.value = "[No model loaded]"
                Log.w(TAG, "WhisperContext is null — no model loaded")
                return
            }

            if (!audioPipeline.hasRecordPermission()) {
                _transcribedText.value = "[Microphone permission required]"
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                return
            }

            audioPipeline.startRecording(viewModelScope)
            _isRecording.value = true
        }
    }

    fun commitText(inputConnection: InputConnection?) {
        val text = _transcribedText.value
        if (text.isNotEmpty() && inputConnection != null) {
            inputConnection.commitText(text, 1)
            _transcribedText.value = ""
        }
    }

    fun onEditAction(action: EditAction, inputConnection: InputConnection?) {
        inputConnection ?: return
        when (action) {
            EditAction.Backspace -> inputConnection.deleteSurroundingText(1, 0)
            EditAction.Comma -> inputConnection.commitText(",", 1)
            EditAction.Space -> inputConnection.commitText(" ", 1)
            EditAction.Period -> inputConnection.commitText(".", 1)
            EditAction.Enter -> {
                // Try editor action first, fall back to newline
                if (!inputConnection.performEditorAction(currentImeAction)) {
                    inputConnection.commitText("\n", 1)
                }
            }
        }
    }

    /**
     * Explicitly release resources. Called from WhisperBoardIME.onDestroy()
     * because this ViewModel is manually constructed and not registered with
     * the ViewModelStore, so onCleared() is never invoked by the framework.
     */
    fun cleanup() {
        whisperContext?.close()
        whisperContext = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
