package com.whisperboard.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class EditAction {
    data object Backspace : EditAction()
    data object Comma : EditAction()
    data object Space : EditAction()
    data object Period : EditAction()
    data object Enter : EditAction()
}

class KeyboardViewModel : ViewModel() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _activeLanguage = MutableStateFlow("auto")
    val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    var currentImeAction: Int = EditorInfo.IME_ACTION_DONE

    fun toggleRecording() {
        // Will be wired to AudioPipeline in Task 4
        _isRecording.value = !_isRecording.value
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
}
