package com.whisperboard.bubble

/**
 * Pure state machine for the **Bubble** input surface.
 *
 * The bubble cycles through four visible states — `Idle`, `Recording`,
 * `Processing`, `Result` — driven by user gestures and asynchronous
 * transcription events. Keeping the machine pure (no Android types, no
 * coroutines) means we can exercise every transition under JVM tests, which
 * matters because the bubble's gesture model — short tap toggles, long press
 * is push-to-talk — has more branches than the IME mic button.
 *
 * The shape mirrors the IME mic button's logic in [com.whisperboard.ui.MicButton]
 * but is not coupled to Compose: events come in, a new state comes out, and
 * the side-effects ([Effect]) the caller should perform are returned alongside
 * the new state. The caller (the [BubbleOverlayService]) is responsible for
 * actually starting/stopping audio capture, calling the engine router, and
 * showing toasts.
 *
 * The bubble surface is independent of the IME — neither requires the other.
 * See `CONTEXT.md` and ADR-0001.
 */
sealed interface BubbleState {
    /** Idle, waiting for the next gesture. */
    data object Idle : BubbleState

    /**
     * Recording in progress.
     * @param pushToTalk True when entered via long press; release will stop.
     *                   False when entered via short tap; the next tap stops.
     */
    data class Recording(val pushToTalk: Boolean) : BubbleState

    /** Audio captured, transcription + post-processing in flight. */
    data object Processing : BubbleState

    /**
     * Transcription complete. The bubble briefly displays [text]; the caller
     * is responsible for the auto-copy side effect (driven by [Effect.AutoCopy])
     * and for transitioning back to [Idle] after the dismiss timer elapses.
     */
    data class Result(val text: String) : BubbleState

    /** The bubble is dismissed; sliver hidden until [cooldownEndsAt]. */
    data class Dismissed(val cooldownEndsAt: Long) : BubbleState

    /**
     * The user tapped the sliver. Sheet is shown with mic + last result.
     * Auto-collapses to Idle after PeekTimeout (4 s of inactivity in the
     * sheet) or on outside tap.
     */
    data object Peeked : BubbleState
}

/**
 * Inputs to the state machine. The caller maps platform gestures and async
 * callbacks onto this vocabulary so the machine never sees Android types.
 */
sealed interface BubbleEvent {
    /** Short tap detected (released before the long-press threshold). */
    data object Tap : BubbleEvent

    /** Long-press threshold crossed — enter push-to-talk mode. */
    data object LongPressStart : BubbleEvent

    /** Push-to-talk released — stop recording. */
    data object LongPressEnd : BubbleEvent

    /** Transcription + post-processing completed; the polished text is in. */
    data class TranscriptReady(val text: String) : BubbleEvent

    /**
     * The user dismissed the result (tap-to-dismiss, swipe, or auto-timeout).
     * Returns to [BubbleState.Idle].
     */
    data object Dismiss : BubbleEvent

    /**
     * Transcription or post-processing failed. The machine returns to
     * [BubbleState.Idle]; the caller is expected to surface a toast via
     * [Effect.ShowError].
     */
    data class Error(val reason: String) : BubbleEvent

    /**
     * User completed a drag-tear gesture. Records dismissedAt = now and
     * computes the cooldown end.
     */
    data class DragTearComplete(val now: Long, val cooldownMs: Long) : BubbleEvent

    /** Cooldown clock fired and reached `cooldownEndsAt`. */
    data object CooldownElapsed : BubbleEvent

    /** User tapped the sliver to open the sheet (peek). */
    data object Peek : BubbleEvent

    /** The 4-second inactivity timer elapsed while in Peeked. */
    data object PeekTimeout : BubbleEvent
}

/**
 * Side-effects the caller should perform after a state transition. The state
 * machine emits effects rather than performing them, so transitions remain
 * testable without mocking platform APIs.
 */
sealed interface BubbleEffect {
    /** Begin audio capture and transcription. */
    data object StartRecording : BubbleEffect

    /**
     * Stop audio capture and dispatch the captured samples through the
     * transcription pipeline. The caller is expected to feed the result back
     * via [BubbleEvent.TranscriptReady] or [BubbleEvent.Error].
     */
    data object StopRecording : BubbleEffect

    /** Auto-copy the polished transcript to the system clipboard. */
    data class AutoCopy(val text: String) : BubbleEffect

    /**
     * Attempt to insert the polished transcript directly into the focused
     * editable field via the accessibility service. Emitted instead of
     * [AutoCopy] when [BubbleStateMachine.accessibilityEnabled] is true.
     *
     * The caller is responsible for executing the insertion and handling
     * the [com.whisperboard.accessibility.WriteResult] — when the writer
     * falls back to clipboard auto-copy the caller routes through the
     * standalone-mode delivery so the user's words are never lost. Per
     * ADR-0001 accessibility is a *pure upgrade*; the fallback path is
     * always available.
     */
    data class InsertInPlace(val text: String) : BubbleEffect

    /** Show a transient "Copied" toast. */
    data object ShowCopiedToast : BubbleEffect

    /**
     * Show a transient "Inserted" confirmation pip. Distinct from
     * [ShowCopiedToast] so the bubble's result UI can render different
     * affordances for in-place insertion vs standalone auto-copy.
     */
    data object ShowInsertedToast : BubbleEffect

    /** Surface a transient error to the user. */
    data class ShowError(val reason: String) : BubbleEffect
}

/** A transition's full result: the new state plus any side-effects to run. */
data class BubbleTransition(
    val state: BubbleState,
    val effects: List<BubbleEffect> = emptyList(),
)

/**
 * Pure transition function. Given the current state and an incoming event,
 * returns the next state and the side-effects the caller should perform.
 *
 * Invalid event/state combinations (e.g. `LongPressStart` while already
 * recording) are silently ignored — the bubble is a single-touch UI surface
 * and the gesture detector is responsible for filtering noise. Returning the
 * existing state with no effects keeps callers' branching trivial.
 */
class BubbleStateMachine(initial: BubbleState = BubbleState.Idle) {

    /**
     * Behaviour controlling whether the auto-copy side-effect fires when a
     * transcript becomes available. Defaults to `true` — the caller may inject
     * a different provider (e.g. one that reads
     * [com.whisperboard.model.BehaviorSettingsRepository.autoCopyEnabled]) at
     * construction time so the machine remains pure.
     */
    var autoCopyEnabled: Boolean = true

    /**
     * Behaviour controlling whether the bubble attempts in-place insertion
     * (via the accessibility service) when a transcript becomes available.
     * Defaults to `false` — accessibility is a *pure upgrade* per ADR-0001
     * and is granted post-install. The caller flips this to `true` once
     * `WhisperBoardAccessibilityService.isEnabled(...)` reports the user has
     * granted the permission.
     *
     * When `true`, the result transition emits [BubbleEffect.InsertInPlace]
     * instead of [BubbleEffect.AutoCopy] / [BubbleEffect.ShowCopiedToast].
     * The caller's response to InsertInPlace owns the fallback path: if the
     * accessibility writer can't find an editable target, the caller routes
     * the same transcript through the standalone-mode auto-copy delivery so
     * the user's words are never lost.
     */
    var accessibilityEnabled: Boolean = false

    private var currentState: BubbleState = initial
    val state: BubbleState get() = currentState

    /**
     * Apply [event] to the current state. Mutates the machine's [state] and
     * returns the [BubbleTransition] for the caller to act on.
     */
    fun handle(event: BubbleEvent): BubbleTransition {
        val transition = transition(currentState, event)
        currentState = transition.state
        return transition
    }

    private fun transition(state: BubbleState, event: BubbleEvent): BubbleTransition =
        when (state) {
            BubbleState.Idle -> handleIdle(event)
            is BubbleState.Recording -> handleRecording(state, event)
            BubbleState.Processing -> handleProcessing(event)
            is BubbleState.Result -> handleResult(state, event)
            BubbleState.Peeked -> handlePeeked(event)
            is BubbleState.Dismissed -> handleDismissed(state, event)
        }

    private fun handleIdle(event: BubbleEvent): BubbleTransition =
        when (event) {
            BubbleEvent.Tap -> BubbleTransition(
                state = BubbleState.Recording(pushToTalk = false),
                effects = listOf(BubbleEffect.StartRecording),
            )
            BubbleEvent.LongPressStart -> BubbleTransition(
                state = BubbleState.Recording(pushToTalk = true),
                effects = listOf(BubbleEffect.StartRecording),
            )
            BubbleEvent.Peek -> BubbleTransition(BubbleState.Peeked)
            is BubbleEvent.DragTearComplete -> BubbleTransition(
                BubbleState.Dismissed(cooldownEndsAt = event.now + event.cooldownMs),
            )
            BubbleEvent.Dismiss,
            BubbleEvent.LongPressEnd -> BubbleTransition(BubbleState.Idle)
            is BubbleEvent.TranscriptReady,
            is BubbleEvent.Error -> BubbleTransition(BubbleState.Idle)
            BubbleEvent.CooldownElapsed,
            BubbleEvent.PeekTimeout -> BubbleTransition(BubbleState.Idle)
        }

    private fun handleRecording(
        state: BubbleState.Recording,
        event: BubbleEvent,
    ): BubbleTransition =
        when (event) {
            // Short tap during toggle-mode recording stops recording.
            // A short tap during push-to-talk recording is ignored — release
            // is what stops the recording.
            BubbleEvent.Tap -> if (state.pushToTalk) {
                BubbleTransition(state)
            } else {
                BubbleTransition(
                    state = BubbleState.Processing,
                    effects = listOf(BubbleEffect.StopRecording),
                )
            }
            BubbleEvent.LongPressEnd -> if (state.pushToTalk) {
                BubbleTransition(
                    state = BubbleState.Processing,
                    effects = listOf(BubbleEffect.StopRecording),
                )
            } else {
                BubbleTransition(state)
            }
            BubbleEvent.LongPressStart -> BubbleTransition(state) // Already recording — ignore.
            is BubbleEvent.Error -> BubbleTransition(
                state = BubbleState.Idle,
                effects = listOf(BubbleEffect.ShowError(event.reason)),
            )
            BubbleEvent.Dismiss -> BubbleTransition(BubbleState.Idle)
            is BubbleEvent.TranscriptReady -> resultTransition(event.text)
            // Edge-sliver gestures are ignored while a recording is in flight —
            // the user must finish (release / tap-stop) or hit Dismiss first.
            is BubbleEvent.DragTearComplete,
            BubbleEvent.CooldownElapsed,
            BubbleEvent.Peek,
            BubbleEvent.PeekTimeout -> BubbleTransition(state)
        }

    private fun handleProcessing(event: BubbleEvent): BubbleTransition =
        when (event) {
            is BubbleEvent.TranscriptReady -> resultTransition(event.text)
            is BubbleEvent.Error -> BubbleTransition(
                state = BubbleState.Idle,
                effects = listOf(BubbleEffect.ShowError(event.reason)),
            )
            BubbleEvent.Dismiss -> BubbleTransition(BubbleState.Idle)
            // Gestures are ignored while transcription is in flight — the
            // bubble UI shows a spinner and is non-interactive.
            BubbleEvent.Tap,
            BubbleEvent.LongPressStart,
            BubbleEvent.LongPressEnd,
            is BubbleEvent.DragTearComplete,
            BubbleEvent.CooldownElapsed,
            BubbleEvent.Peek,
            BubbleEvent.PeekTimeout -> BubbleTransition(BubbleState.Processing)
        }

    private fun handleResult(
        state: BubbleState.Result,
        event: BubbleEvent,
    ): BubbleTransition =
        when (event) {
            BubbleEvent.Dismiss -> BubbleTransition(BubbleState.Idle)
            // A short tap on the expanded result dismisses it; this matches
            // the IME's preview-then-commit "tap to send" affordance, except
            // here the auto-copy already happened so dismissing just collapses
            // the bubble.
            BubbleEvent.Tap -> BubbleTransition(BubbleState.Idle)
            // A long-press on the result starts a fresh recording — short
            // path back to dictation without first dismissing.
            BubbleEvent.LongPressStart -> BubbleTransition(
                state = BubbleState.Recording(pushToTalk = true),
                effects = listOf(BubbleEffect.StartRecording),
            )
            BubbleEvent.LongPressEnd -> BubbleTransition(state)
            is BubbleEvent.TranscriptReady -> resultTransition(event.text)
            is BubbleEvent.Error -> BubbleTransition(
                state = BubbleState.Idle,
                effects = listOf(BubbleEffect.ShowError(event.reason)),
            )
            // Edge-sliver gestures don't apply while the result chip is up.
            is BubbleEvent.DragTearComplete,
            BubbleEvent.CooldownElapsed,
            BubbleEvent.Peek,
            BubbleEvent.PeekTimeout -> BubbleTransition(state)
        }

    /**
     * Sliver-tap "peek" sheet. The user can long-press the mic to start a
     * fresh PTT recording, tap again to close, or wait for the inactivity
     * timer to fire [BubbleEvent.PeekTimeout].
     */
    private fun handlePeeked(event: BubbleEvent): BubbleTransition =
        when (event) {
            BubbleEvent.PeekTimeout -> BubbleTransition(BubbleState.Idle)
            // Re-tap on the sliver while peeked closes the sheet.
            BubbleEvent.Tap -> BubbleTransition(BubbleState.Idle)
            // Long-press on the peek sheet's mic enters PTT recording — same
            // as the "start a fresh recording from result" shortcut.
            BubbleEvent.LongPressStart -> BubbleTransition(
                state = BubbleState.Recording(pushToTalk = true),
                effects = listOf(BubbleEffect.StartRecording),
            )
            // No transition for any other event — peek is an ephemeral overlay
            // and async events (transcripts, errors) shouldn't apply here.
            BubbleEvent.LongPressEnd,
            BubbleEvent.Dismiss,
            BubbleEvent.Peek,
            BubbleEvent.CooldownElapsed,
            is BubbleEvent.DragTearComplete,
            is BubbleEvent.TranscriptReady,
            is BubbleEvent.Error -> BubbleTransition(BubbleState.Peeked)
        }

    /**
     * The bubble is dismissed for a cooldown window. The only meaningful
     * event is [BubbleEvent.CooldownElapsed], which restores the sliver to
     * [BubbleState.Idle]. Re-issuing [BubbleEvent.DragTearComplete] while
     * already dismissed is a no-op — there's no point dismissing twice.
     */
    private fun handleDismissed(
        state: BubbleState.Dismissed,
        event: BubbleEvent,
    ): BubbleTransition =
        when (event) {
            BubbleEvent.CooldownElapsed -> BubbleTransition(BubbleState.Idle)
            BubbleEvent.Tap,
            BubbleEvent.LongPressStart,
            BubbleEvent.LongPressEnd,
            BubbleEvent.Dismiss,
            BubbleEvent.Peek,
            BubbleEvent.PeekTimeout,
            is BubbleEvent.DragTearComplete,
            is BubbleEvent.TranscriptReady,
            is BubbleEvent.Error -> BubbleTransition(state)
        }

    private fun resultTransition(text: String): BubbleTransition {
        if (text.isBlank()) {
            // Nothing meaningful was transcribed — go back to idle silently.
            return BubbleTransition(BubbleState.Idle)
        }
        val effects = mutableListOf<BubbleEffect>()
        // Accessibility is the upgrade path: when it is granted, attempt
        // in-place insertion instead of clipboard auto-copy. The caller is
        // responsible for routing back through clipboard delivery if the
        // accessibility writer falls back (no editable target / action
        // refused) — see BubbleAccessibilityDelivery.
        if (accessibilityEnabled) {
            effects += BubbleEffect.InsertInPlace(text)
        } else if (autoCopyEnabled) {
            effects += BubbleEffect.AutoCopy(text)
            effects += BubbleEffect.ShowCopiedToast
        }
        return BubbleTransition(
            state = BubbleState.Result(text),
            effects = effects,
        )
    }
}
