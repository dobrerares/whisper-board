package com.whisperboard.bubble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bubble's standalone-mode analogue of [com.whisperboard.ui.TranscriptDelivery].
 *
 * Two paths exist for a finished, polished transcript that arrives at the
 * bubble:
 *
 * - **Auto-copy** (default per CONTEXT.md): the transcript is emitted on
 *   [autoCopyRequests] so the overlay service can write it to the system
 *   clipboard. The expanded result view still shows the text — auto-copy is
 *   in addition to display, not in lieu of it.
 *
 * - **Manual copy** (auto-copy off): the transcript is staged in
 *   [transcribedText] for the user to read and copy via a button. No
 *   clipboard write happens automatically.
 *
 * Pulled out of the overlay service so the routing decision can be unit
 * tested without faking `ClipboardManager`, `WindowManager`, or any other
 * `Context`-bound singleton. Mirrors the shape of `TranscriptDelivery`
 * intentionally — same event-vs-state split, same provider-on-each-call
 * pattern so flipping the toggle in Settings affects the *next* utterance
 * rather than recoupling to a flow.
 */
class BubbleClipboardDelivery(
    private val autoCopyEnabledProvider: suspend () -> Boolean,
) {
    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _autoCopyRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val autoCopyRequests: SharedFlow<String> = _autoCopyRequests.asSharedFlow()

    /**
     * Route [finalText] to the clipboard (auto-copy on) or stage it for
     * manual copy (auto-copy off). Empty input clears any staged transcript
     * and emits nothing.
     *
     * Returns `true` iff an auto-copy event was emitted, so the caller can
     * show the "Copied" toast at the right moment without re-reading the
     * provider.
     */
    suspend fun deliver(finalText: String): Boolean {
        if (finalText.isEmpty()) {
            _transcribedText.value = ""
            return false
        }
        // Always stage the text so the expanded result view in the overlay
        // can show it; auto-copy *also* writes to the clipboard.
        _transcribedText.value = finalText
        return if (autoCopyEnabledProvider()) {
            _autoCopyRequests.tryEmit(finalText)
            true
        } else {
            false
        }
    }

    /**
     * Clear the staged transcript. Called when the user dismisses the
     * expanded result, when the bubble returns to idle, or when the service
     * tears down.
     */
    fun clearStaged() {
        _transcribedText.value = ""
    }
}
