package com.whisperboard.ui

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.LanguageRepository
import com.whisperboard.postprocessing.PostProcessingContext
import com.whisperboard.postprocessing.PostProcessingOutcome
import com.whisperboard.postprocessing.PostProcessingRouter
import com.whisperboard.transcription.EngineRouter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val languageRepository: LanguageRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "KeyboardViewModel"
    }

    @Volatile
    private var engineRouter: EngineRouter? = null

    @Volatile
    private var postProcessingRouter: PostProcessingRouter? = null

    /** Language snapshot taken when recording starts — used for transcription. */
    private var recordingLanguage: String = "auto"

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    /**
     * When true, the most recent transcript came back as raw text because the
     * post-processor was attempted but failed. Used to surface a small
     * "polish unavailable" indicator next to the transcript.
     */
    private val _polishUnavailable = MutableStateFlow(false)
    val polishUnavailable: StateFlow<Boolean> = _polishUnavailable.asStateFlow()

    val activeLanguage: StateFlow<String> = languageRepository.activeLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    val favoriteLanguages: StateFlow<Set<String>> = languageRepository.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    val waveformData: StateFlow<FloatArray> = audioPipeline.waveformData

    var currentImeAction: Int = EditorInfo.IME_ACTION_DONE

    fun setEngineRouter(router: EngineRouter?) {
        engineRouter = router
    }

    fun setPostProcessingRouter(router: PostProcessingRouter?) {
        postProcessingRouter = router
    }

    fun startRecording() {
        if (_isRecording.value) return
        val router = engineRouter
        if (router == null || !router.canTranscribe()) {
            _errorMessage.tryEmit("No transcription engine available")
            Log.w(TAG, "EngineRouter unavailable or no engine configured")
            return
        }
        if (!audioPipeline.hasRecordPermission()) {
            _errorMessage.tryEmit("Microphone permission required")
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }
        recordingLanguage = activeLanguage.value
        audioPipeline.startRecording(viewModelScope)
        _isRecording.value = true
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        viewModelScope.launch {
            try {
                val samples = audioPipeline.stopRecording()
                if (samples.isEmpty()) {
                    Log.w(TAG, "No audio samples captured")
                    return@launch
                }
                val router = engineRouter
                if (router == null || !router.canTranscribe()) {
                    _errorMessage.tryEmit("No transcription engine available")
                    Log.w(TAG, "EngineRouter unavailable or no engine configured")
                    return@launch
                }
                _isProcessing.value = true
                val start = System.currentTimeMillis()
                val rawTranscript = router.transcribe(samples, recordingLanguage)
                val elapsed = System.currentTimeMillis() - start
                Log.d(TAG, "Transcription done in ${elapsed}ms: \"$rawTranscript\"")

                val finalText = polishIfEnabled(rawTranscript)
                _transcribedText.value = finalText
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                _errorMessage.tryEmit(e.message ?: "Transcription failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun commitText(inputConnection: InputConnection?) {
        val text = _transcribedText.value
        if (text.isNotEmpty() && inputConnection != null) {
            inputConnection.commitText(text, 1)
            _transcribedText.value = ""
            _polishUnavailable.value = false
        }
    }

    /**
     * Run the raw transcript through the post-processor when one is wired and
     * polish mode is on. Returns the raw transcript unchanged when polish is
     * skipped or fails — words are never lost. Updates [polishUnavailable]
     * so the UI can show a small indicator when fallback occurred.
     */
    private suspend fun polishIfEnabled(rawTranscript: String): String {
        val postRouter = postProcessingRouter ?: run {
            _polishUnavailable.value = false
            return rawTranscript
        }
        val outcome = postRouter.polish(
            rawTranscript = rawTranscript,
            context = PostProcessingContext(),
        )
        return when (outcome) {
            is PostProcessingOutcome.Polished -> {
                _polishUnavailable.value = false
                outcome.text
            }
            is PostProcessingOutcome.Skipped -> {
                _polishUnavailable.value = false
                outcome.text
            }
            is PostProcessingOutcome.Fallback -> {
                Log.w(TAG, "Post-processing fell back to raw: ${outcome.reason}")
                _polishUnavailable.value = true
                outcome.text
            }
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
                if (!inputConnection.performEditorAction(currentImeAction)) {
                    inputConnection.commitText("\n", 1)
                }
            }
        }
    }

    fun setLanguage(code: String) {
        viewModelScope.launch {
            languageRepository.setActiveLanguage(code)
        }
    }

    fun toggleFavorite(code: String) {
        viewModelScope.launch {
            val current = favoriteLanguages.value
            if (code in current) {
                languageRepository.removeFavorite(code)
            } else {
                languageRepository.addFavorite(code)
            }
        }
    }

    fun cleanup() {
        engineRouter = null
        postProcessingRouter = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
