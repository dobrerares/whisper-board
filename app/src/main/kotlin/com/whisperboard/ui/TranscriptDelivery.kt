package com.whisperboard.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides where a finished, polished transcript goes once a recording
 * completes. Two paths:
 *
 * - **Auto-insert** (default per CONTEXT.md): the transcript is emitted on
 *   [autoInsertRequests] so the IME (or any other surface) can write it into
 *   the focused field via `InputConnection.commitText`. The staged
 *   [transcribedText] is cleared in this path so the user does not see the
 *   same words in two places at once. Slice 6b replaces the cleared area
 *   with a tap-to-reinsert history scroll — see `TranscriptionArea`.
 *
 * - **Preview-then-commit** (auto-insert off): the transcript is staged in
 *   [transcribedText] for the user to review and tap to send.
 *
 * Pulled out of `KeyboardViewModel` so the routing decision can be unit-tested
 * without faking [com.whisperboard.audio.AudioPipeline] or
 * [com.whisperboard.model.LanguageRepository] (both `Context`-bound).
 *
 * Slice 6b adds the `targetAppNameProvider` callback so the delivery can
 * surface the focused field's owning package name to the persistence layer.
 * The provider is read on every [deliver] call so the value is the *current*
 * package name at delivery time, not whatever it was when the IME bound.
 * The data layer never touches Android primitives directly — the IME
 * forwards its `currentInputEditorInfo.packageName` through this provider.
 */
class TranscriptDelivery(
    private val autoInsertEnabledProvider: suspend () -> Boolean,
    private val targetAppNameProvider: () -> String? = { null },
) {
    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _autoInsertRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoInsertRequests: SharedFlow<String> = _autoInsertRequests.asSharedFlow()

    /**
     * The package name of the focused field at the moment the most recent
     * delivery routed. Held as a snapshot so callers (the persistence layer)
     * can read it after [deliver] returns without re-asking the IME for the
     * "current" value, which may have changed.
     *
     * Null means we couldn't see a focused field — usually the bubble's
     * standalone path, or the IME between input bindings.
     */
    @Volatile
    private var lastTargetAppName: String? = null

    /** The package name captured at the most recent [deliver] call. */
    fun lastTargetAppName(): String? = lastTargetAppName

    /**
     * Route [finalText] to the focused field (auto-insert) or to the staged
     * preview area (auto-insert off). Empty input clears any staged preview
     * and emits nothing.
     *
     * Captures the focused-field package name into [lastTargetAppName] so
     * the persistence layer can record it without reaching into the IME.
     */
    suspend fun deliver(finalText: String) {
        if (finalText.isEmpty()) {
            _transcribedText.value = ""
            return
        }
        lastTargetAppName = targetAppNameProvider()
        if (autoInsertEnabledProvider()) {
            _autoInsertRequests.tryEmit(finalText)
            // The IME's transcript area was cleared here pre-slice-6b. With
            // the history scroll in place the area is no longer empty —
            // staged text stays empty (the scroll renders entries) and the
            // user sees their just-spoken utterance at the top of the
            // history list a moment later when persistence completes.
            _transcribedText.value = ""
        } else {
            _transcribedText.value = finalText
        }
    }

    /**
     * Clear any staged preview transcript. Called from the user-tap commit
     * path after the IC commit succeeds.
     */
    fun clearStaged() {
        _transcribedText.value = ""
    }
}
