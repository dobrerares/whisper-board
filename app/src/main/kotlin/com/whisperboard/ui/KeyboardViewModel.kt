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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * Returns whether auto-insert is currently enabled. Default returns `true`
     * so existing call sites (and tests) keep working until the IME wires the
     * real `BehaviorSettingsRepository` flow through.
     */
    autoInsertEnabledProvider: suspend () -> Boolean = { true },
    /**
     * How long the active-language chip flashes the *detected* language after
     * each utterance before reverting to the user's selected state. Pulled
     * out as a constructor parameter so tests can collapse it to zero.
     */
    private val detectedLanguageFlashMs: Long = DETECTED_FLASH_MS_DEFAULT,
) : ViewModel() {

    companion object {
        private const val TAG = "KeyboardViewModel"

        /** Default flash duration (~1.5s per the slice 5 brief). */
        const val DETECTED_FLASH_MS_DEFAULT: Long = 1_500L
    }

    @Volatile
    private var engineRouter: EngineRouter? = null

    @Volatile
    private var postProcessingRouter: PostProcessingRouter? = null

    /** Language snapshot taken when recording starts — used for transcription. */
    private var recordingLanguage: String = "auto"

    /** Coroutine job for the chip's "detected language" flash; cancelled per utterance. */
    @Volatile
    private var flashJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * Decides where each finished transcript goes — focused field (auto-insert)
     * or staged preview (auto-insert off). Pulled out so the decision is
     * unit-testable without faking [AudioPipeline] / [LanguageRepository].
     */
    private val delivery = TranscriptDelivery(autoInsertEnabledProvider)

    val transcribedText: StateFlow<String> = delivery.transcribedText

    /**
     * One-shot events asking the IME to write text into the focused field via
     * `InputConnection.commitText`. Emitted when **auto-insert** is on and a
     * transcript is ready. The screen layer subscribes and applies the commit;
     * the view-model stays free of Android `InputConnection` references in the
     * auto-insert path.
     */
    val autoInsertRequests: SharedFlow<String> = delivery.autoInsertRequests

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

    /**
     * The user's declared **language profile** — the set of languages they
     * speak. Sourced from [LanguageRepository.spokenLanguages]. Consumed
     * unconditionally by [PostProcessingContext] so the post-processor's
     * system prompt can do code-switching recovery and proper-noun spelling.
     * Independent of [activeLanguage] — pinning the chip does not touch the
     * profile (per CONTEXT.md and ADR-0003).
     */
    val spokenLanguages: StateFlow<Set<String>> = languageRepository.spokenLanguages
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            LanguageRepository.DEFAULT_SPOKEN_LANGUAGES,
        )

    /**
     * Transient state for the active-language chip's "I just detected X" flash.
     * Non-null for [detectedLanguageFlashMs] after each utterance, then nulled
     * out. The chip composable prefers this value over [activeLanguage] when
     * non-null. Detection visibility builds trust; the chip's persistent
     * selection is unchanged.
     *
     * NOTE: whisper.cpp's per-segment detected-language metadata is not
     * exposed through the JNI bridge today, so we flash whatever the chip
     * selection was — `auto` for the auto-detect case, the pinned code
     * otherwise. A real per-utterance readout is a follow-up; the contract
     * (a flash exists, lasts ~1.5s, reverts) is in place so the wiring is
     * unchanged when the metadata lands.
     */
    private val _detectedLanguageFlash = MutableStateFlow<String?>(null)
    val detectedLanguageFlash: StateFlow<String?> = _detectedLanguageFlash.asStateFlow()

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

                // Flash the chip with the language we asked Whisper to use.
                // When the chip is on auto, we don't yet know what was
                // detected (segment metadata not surfaced through JNI); we
                // still flash so the user sees the chip behave consistently.
                flashDetectedLanguage(recordingLanguage)

                val finalText = polishIfEnabled(rawTranscript)
                delivery.deliver(finalText)
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

    /**
     * Commit the staged transcript into the focused field. Used by the
     * preview-then-commit path (auto-insert OFF) and as the user-tap fallback
     * if an auto-insert event was missed. No-op when the staged transcript is
     * empty.
     */
    fun commitText(inputConnection: InputConnection?) {
        val text = delivery.transcribedText.value
        if (text.isNotEmpty() && inputConnection != null) {
            inputConnection.commitText(text, 1)
            delivery.clearStaged()
            _polishUnavailable.value = false
        }
    }

    /**
     * Surface a transient flash of the detected language on the chip. The
     * chip composable prefers this value over the persistent [activeLanguage]
     * when non-null. Cancels any previous flash so a quick succession of
     * utterances doesn't pile up overlapping reverts.
     */
    private fun flashDetectedLanguage(language: String) {
        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            _detectedLanguageFlash.value = language
            delay(detectedLanguageFlashMs)
            _detectedLanguageFlash.value = null
        }
    }

    /**
     * Run the raw transcript through the post-processor when one is wired and
     * polish mode is on. Returns the raw transcript unchanged when polish is
     * skipped or fails — words are never lost. Updates [polishUnavailable]
     * so the UI can show a small indicator when fallback occurred.
     *
     * The user's declared language profile flows through here:
     * `spokenLanguages.value` -> `PostProcessingContext` -> `PromptBuilder`
     * -> the LLM's system prompt. This is the entire profile-to-LLM data
     * path that ADR-0003 spells out.
     */
    private suspend fun polishIfEnabled(rawTranscript: String): String {
        val postRouter = postProcessingRouter ?: run {
            _polishUnavailable.value = false
            return rawTranscript
        }
        val outcome = postRouter.polish(
            rawTranscript = rawTranscript,
            context = PostProcessingContext(
                languageProfile = spokenLanguages.value,
            ),
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
