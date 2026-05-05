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
 *   same words in two places at once.
 *
 * - **Preview-then-commit** (auto-insert off): the transcript is staged in
 *   [transcribedText] for the user to review and tap to send.
 *
 * Pulled out of `KeyboardViewModel` so the routing decision can be unit-tested
 * without faking [com.whisperboard.audio.AudioPipeline] or
 * [com.whisperboard.model.LanguageRepository] (both `Context`-bound).
 */
class TranscriptDelivery(
    private val autoInsertEnabledProvider: suspend () -> Boolean,
) {
    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _autoInsertRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoInsertRequests: SharedFlow<String> = _autoInsertRequests.asSharedFlow()

    /**
     * Route [finalText] to the focused field (auto-insert) or to the staged
     * preview area (auto-insert off). Empty input clears any staged preview
     * and emits nothing.
     */
    suspend fun deliver(finalText: String) {
        if (finalText.isEmpty()) {
            _transcribedText.value = ""
            return
        }
        if (autoInsertEnabledProvider()) {
            _autoInsertRequests.tryEmit(finalText)
            // Slice 6b (issue #8) replaces this cleared area with a dictation
            // history scroll. Until then we clear it so the just-committed
            // text does not also appear in the IME's preview.
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
