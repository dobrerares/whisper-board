package com.whisperboard.bubble

import com.whisperboard.accessibility.FallbackReason
import com.whisperboard.accessibility.InsertMethod
import com.whisperboard.accessibility.WriteResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bubble's *in-place insertion* analogue of [BubbleClipboardDelivery].
 *
 * Sibling — not subclass — of the clipboard delivery: the two have the same
 * shape (provider-on-each-call gate + staged transcript + event flow) but
 * write to different surfaces. Per the Agent Brief: "mirror the shape; keep
 * the two as siblings — neither subclasses the other."
 *
 * Two paths:
 *
 * - **Insertion succeeded** ([WriteResult.Inserted]): the transcript is
 *   staged in [insertedText] for the result UI to render the in-place
 *   confirmation pip; an event fires on [insertions] so the overlay
 *   service can show a tiny confirmation toast or animate the bubble.
 *
 * - **Insertion fell back** ([WriteResult.FellBackToClipboard]): the
 *   transcript is *not* staged here — the caller hands the same text to
 *   [BubbleClipboardDelivery] so the standalone-mode result UI takes over.
 *   The fallback reason is exposed on [fallbacks] so the caller can show a
 *   reason-specific toast ("Couldn't find a text field — copied instead").
 *
 * The actual writing happens outside this class: production wiring calls
 * `WhisperBoardAccessibilityService.tryWrite(...)` and feeds the result
 * back via [deliver]. This split is deliberate — the writer requires the
 * Android service binding, but the delivery routing is pure and can be
 * unit-tested with synthetic [WriteResult] values.
 *
 * Mirrors `BubbleClipboardDelivery` and `TranscriptDelivery` for consistency.
 */
class BubbleAccessibilityDelivery(
    private val accessibilityEnabledProvider: suspend () -> Boolean,
) {
    private val _insertedText = MutableStateFlow("")
    val insertedText: StateFlow<String> = _insertedText.asStateFlow()

    private val _insertions = MutableSharedFlow<InsertedEvent>(extraBufferCapacity = 4)
    val insertions: SharedFlow<InsertedEvent> = _insertions.asSharedFlow()

    private val _fallbacks = MutableSharedFlow<FallbackReason>(extraBufferCapacity = 4)
    val fallbacks: SharedFlow<FallbackReason> = _fallbacks.asSharedFlow()

    /**
     * Route a [WriteResult] returned by the accessibility writer.
     *
     * Returns [DeliveryOutcome.Inserted] iff the writer wrote to the
     * focused field — the caller skips the clipboard path. Otherwise
     * returns [DeliveryOutcome.FellBack] and the caller is responsible for
     * routing the same transcript through the clipboard delivery so the
     * user's words are preserved.
     *
     * Empty [text] is reported as [DeliveryOutcome.FellBack] without
     * staging or emitting; the caller may bypass clipboard delivery as well.
     *
     * Reads [accessibilityEnabledProvider] on each call so toggling the
     * permission affects the *next* utterance — same pattern as the
     * clipboard delivery's auto-copy provider.
     */
    suspend fun deliver(text: String, result: WriteResult): DeliveryOutcome {
        if (text.isEmpty()) {
            _insertedText.value = ""
            return DeliveryOutcome.FellBack(FallbackReason.NoEditableTarget)
        }
        if (!accessibilityEnabledProvider()) {
            // The service was revoked between transcription start and
            // delivery. Report a fallback so the caller routes to clipboard.
            return DeliveryOutcome.FellBack(FallbackReason.NoEditableTarget)
        }
        return when (result) {
            is WriteResult.Inserted -> {
                _insertedText.value = text
                _insertions.tryEmit(InsertedEvent(text = text, method = result.method))
                DeliveryOutcome.Inserted(result.method)
            }
            is WriteResult.FellBackToClipboard -> {
                // Don't stage — the clipboard delivery will surface the
                // standalone-mode result UI in this case.
                _insertedText.value = ""
                _fallbacks.tryEmit(result.reason)
                DeliveryOutcome.FellBack(result.reason)
            }
        }
    }

    /** Clear the staged inserted-text preview when the bubble returns to idle. */
    fun clearStaged() {
        _insertedText.value = ""
    }
}

/**
 * What the delivery actually did. The bubble overlay service uses this to
 * skip the clipboard write when in-place insertion succeeded.
 */
sealed interface DeliveryOutcome {
    data class Inserted(val method: InsertMethod) : DeliveryOutcome
    data class FellBack(val reason: FallbackReason) : DeliveryOutcome
}

/** Event payload for [BubbleAccessibilityDelivery.insertions]. */
data class InsertedEvent(val text: String, val method: InsertMethod)
