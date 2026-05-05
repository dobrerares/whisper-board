package com.whisperboard.ui

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.BehaviorSettingsRepository
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.history.DictationEntry
import com.whisperboard.model.history.DictationHistoryRepository
import com.whisperboard.model.history.HistoryRetention
import com.whisperboard.model.history.HistorySettingsRepository
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
     * The package name of the focused field's owning app, surfaced to the
     * [TranscriptDelivery] callback so each persisted dictation entry can
     * record where the words went. Defaults to `null` so headless tests
     * don't need to fake an `EditorInfo`.
     */
    targetAppNameProvider: () -> String? = { null },
    /**
     * History persistence. Optional so headless tests (and the bubble
     * service, which has its own history call site) can construct a
     * KeyboardViewModel without a Room database.
     */
    private val historyRepository: DictationHistoryRepository? = null,
    /**
     * Privacy / retention settings. Optional alongside [historyRepository] —
     * when null, the IME falls back to the pre-slice-6b single-utterance
     * preview behaviour.
     */
    private val historySettings: HistorySettingsRepository? = null,
    /**
     * Behavior settings (auto-insert / auto-copy). Optional so headless
     * tests can still construct the view-model. When wired, the IME's
     * transcript area uses [autoInsertEnabled] (alongside [historyRetention])
     * to choose between the history scroll and the single-utterance preview.
     */
    behaviorSettings: BehaviorSettingsRepository? = null,
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

        /**
         * Maximum entries surfaced in the IME's transcript-area history
         * scroll. The Settings → History page renders the unbounded list.
         * 50 is large enough to feel "scrollable" without making the IME
         * lazy column carry more than a screenful past the immediate
         * context, and is comfortably below the smallest configurable
         * retention (25). When retention is set lower than this limit the
         * scroll naturally renders fewer rows.
         */
        const val HISTORY_PREVIEW_LIMIT: Int = 50
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
     *
     * The `targetAppNameProvider` is forwarded so the delivery snapshots the
     * focused field's package name at delivery time; persistence reads it
     * from `delivery.lastTargetAppName()`.
     */
    private val delivery = TranscriptDelivery(
        autoInsertEnabledProvider = autoInsertEnabledProvider,
        targetAppNameProvider = targetAppNameProvider,
    )

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

    /**
     * Live retention picker value. Sourced from [HistorySettingsRepository]
     * when wired; defaults to [HistoryRetention.OneHundred] for tests that
     * don't pass settings through. The IME's history scroll uses this to
     * decide whether to render the scroll at all (off ⇒ fall back to the
     * single-utterance preview).
     */
    val historyRetention: StateFlow<HistoryRetention> =
        (historySettings?.retention ?: flowOf(HistorySettingsRepository.DEFAULT_RETENTION))
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                HistorySettingsRepository.DEFAULT_RETENTION,
            )

    /**
     * Live auto-insert toggle. The IME's transcript area uses this with
     * [historyRetention] to decide between the history scroll (auto-insert
     * ON + retention ON) and the single-utterance preview (anything else).
     */
    val autoInsertEnabled: StateFlow<Boolean> =
        (behaviorSettings?.autoInsertEnabled ?: flowOf(BehaviorSettingsRepository.DEFAULT_AUTO_INSERT))
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                BehaviorSettingsRepository.DEFAULT_AUTO_INSERT,
            )

    /**
     * Newest-first stream of recent dictation entries for the IME's history
     * scroll. Switches to an empty list whenever retention is off, so the
     * UI can fall back to the single-utterance preview cleanly without a
     * separate "is history on" check.
     *
     * Capped at [HISTORY_PREVIEW_LIMIT] for the IME view; the Settings →
     * History page renders the unbounded `all()` flow instead.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recentDictationEntries: StateFlow<List<DictationEntry>> = run {
        val repo = historyRepository
        val settings = historySettings
        val flow = if (repo != null && settings != null) {
            settings.retention.flatMapLatest { retention ->
                if (retention.isEnabled) repo.recent(HISTORY_PREVIEW_LIMIT)
                else flowOf(emptyList())
            }
        } else {
            flowOf(emptyList())
        }
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

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
                persistDictationEntry(
                    rawTranscript = rawTranscript,
                    polishedTranscript = finalText,
                    detectedLanguage = recordingLanguage,
                )
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

    /**
     * Re-commit a previously persisted entry into the focused field. Used by
     * the IME's history scroll: tapping an entry replays its polished text.
     * No-op when the input connection is null (nothing focused) or the
     * entry's polished text is empty.
     */
    fun reinsertEntry(entry: DictationEntry, inputConnection: InputConnection?) {
        if (inputConnection == null || entry.polishedTranscript.isEmpty()) return
        inputConnection.commitText(entry.polishedTranscript, 1)
    }

    /**
     * Delete an entry by id from the persisted history. Used by the
     * long-press context menu and the swipe-to-delete gesture in the
     * history scroll. No-op when no repository is wired.
     */
    fun deleteEntry(id: Long) {
        val repo = historyRepository ?: return
        viewModelScope.launch {
            repo.delete(id)
        }
    }

    /**
     * Persist a freshly delivered entry. Reads the package name snapshot
     * captured by [delivery] at delivery time, so the value reflects where
     * the words actually went rather than wherever focus may have moved
     * to since. No-ops when no [historyRepository] is wired or when the
     * polished text is blank.
     */
    private fun persistDictationEntry(
        rawTranscript: String,
        polishedTranscript: String,
        detectedLanguage: String,
    ) {
        val repo = historyRepository ?: return
        if (polishedTranscript.isBlank() && rawTranscript.isBlank()) return
        val targetApp = delivery.lastTargetAppName()
        // Persist the language string we asked Whisper to use. Per the brief
        // this is the detected language for the utterance — when the chip
        // is on `auto` we don't yet know what Whisper actually picked, so
        // an empty `detectedLanguages` string is honest: "unknown".
        val languageField = if (detectedLanguage == "auto" || detectedLanguage.isBlank()) {
            ""
        } else {
            detectedLanguage
        }
        viewModelScope.launch {
            try {
                repo.record(
                    DictationEntry(
                        polishedTranscript = polishedTranscript,
                        rawTranscript = rawTranscript,
                        timestampMs = System.currentTimeMillis(),
                        detectedLanguages = languageField,
                        targetAppName = targetApp,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist dictation entry", e)
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
