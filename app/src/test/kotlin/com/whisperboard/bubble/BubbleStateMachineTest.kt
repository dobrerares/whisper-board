package com.whisperboard.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers every event-from-every-state transition for the bubble state
 * machine. The bubble's gesture model has more branches than the IME mic
 * button — short tap toggles vs long press is push-to-talk vs the "tap result
 * to dismiss, long-press result to re-record" shortcuts — so each one is
 * exercised explicitly.
 *
 * The auto-copy effect is governed by [BubbleStateMachine.autoCopyEnabled];
 * its on/off paths are both tested. Empty transcripts must never produce a
 * result state nor a copy effect — the brief calls them out as a no-op.
 */
class BubbleStateMachineTest {

    // --- Idle -> Recording (toggle / PTT) ---

    @Test
    fun `tap from idle starts toggle-mode recording and emits StartRecording`() {
        val machine = BubbleStateMachine()

        val transition = machine.handle(BubbleEvent.Tap)

        assertEquals(BubbleState.Recording(pushToTalk = false), transition.state)
        assertEquals(listOf(BubbleEffect.StartRecording), transition.effects)
        assertEquals(BubbleState.Recording(pushToTalk = false), machine.state)
    }

    @Test
    fun `long-press start from idle enters push-to-talk and emits StartRecording`() {
        val machine = BubbleStateMachine()

        val transition = machine.handle(BubbleEvent.LongPressStart)

        assertEquals(BubbleState.Recording(pushToTalk = true), transition.state)
        assertEquals(listOf(BubbleEffect.StartRecording), transition.effects)
    }

    @Test
    fun `dismiss while idle is a no-op`() {
        val machine = BubbleStateMachine()
        val transition = machine.handle(BubbleEvent.Dismiss)
        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    // --- Recording -> Processing transitions ---

    @Test
    fun `tap during toggle-mode recording stops recording`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap) // -> Recording(toggle)

        val transition = machine.handle(BubbleEvent.Tap)

        assertEquals(BubbleState.Processing, transition.state)
        assertEquals(listOf(BubbleEffect.StopRecording), transition.effects)
    }

    @Test
    fun `tap during push-to-talk is ignored, only release stops`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.LongPressStart) // -> Recording(ptt)

        val tap = machine.handle(BubbleEvent.Tap)
        assertEquals(BubbleState.Recording(pushToTalk = true), tap.state)
        assertTrue(tap.effects.isEmpty())

        val release = machine.handle(BubbleEvent.LongPressEnd)
        assertEquals(BubbleState.Processing, release.state)
        assertEquals(listOf(BubbleEffect.StopRecording), release.effects)
    }

    @Test
    fun `long-press end during toggle-mode recording is a no-op`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap) // -> Recording(toggle)

        val transition = machine.handle(BubbleEvent.LongPressEnd)

        assertEquals(BubbleState.Recording(pushToTalk = false), transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `long-press start during recording is ignored`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap) // -> Recording(toggle)

        val transition = machine.handle(BubbleEvent.LongPressStart)

        assertEquals(BubbleState.Recording(pushToTalk = false), transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `error during recording falls back to idle and surfaces ShowError`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap) // -> Recording(toggle)

        val transition = machine.handle(BubbleEvent.Error("mic busy"))

        assertEquals(BubbleState.Idle, transition.state)
        assertEquals(listOf<BubbleEffect>(BubbleEffect.ShowError("mic busy")), transition.effects)
    }

    @Test
    fun `dismiss during recording returns to idle without effects`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.Dismiss)

        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    // --- Processing -> Result with auto-copy on ---

    @Test
    fun `transcript ready in processing emits Result plus auto-copy when enabled`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = true }
        machine.handle(BubbleEvent.Tap) // Idle -> Recording
        machine.handle(BubbleEvent.Tap) // Recording -> Processing

        val transition = machine.handle(BubbleEvent.TranscriptReady("Hello world."))

        assertEquals(BubbleState.Result("Hello world."), transition.state)
        assertEquals(
            listOf<BubbleEffect>(
                BubbleEffect.AutoCopy("Hello world."),
                BubbleEffect.ShowCopiedToast,
            ),
            transition.effects,
        )
    }

    @Test
    fun `transcript ready with auto-copy disabled still surfaces result but skips copy`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = false }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("manual copy please"))

        assertEquals(BubbleState.Result("manual copy please"), transition.state)
        assertTrue(
            "auto-copy off must not emit AutoCopy/ShowCopiedToast effects",
            transition.effects.isEmpty(),
        )
    }

    @Test
    fun `blank transcript returns to idle and does not emit auto-copy`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = true }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("   "))

        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `error during processing falls back to idle and surfaces ShowError`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.Error("network down"))

        assertEquals(BubbleState.Idle, transition.state)
        assertEquals(listOf<BubbleEffect>(BubbleEffect.ShowError("network down")), transition.effects)
    }

    @Test
    fun `gestures during processing are silently absorbed`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap) // Now Processing

        for (event in listOf(BubbleEvent.Tap, BubbleEvent.LongPressStart, BubbleEvent.LongPressEnd)) {
            val transition = machine.handle(event)
            assertEquals(
                "Processing should swallow $event without leaving the state",
                BubbleState.Processing,
                transition.state,
            )
            assertTrue(transition.effects.isEmpty())
        }
    }

    // --- Result-state interactions ---

    @Test
    fun `tap on result dismisses to idle without re-emitting copy`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = true }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.TranscriptReady("words."))

        val transition = machine.handle(BubbleEvent.Tap)

        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `dismiss on result returns to idle`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.TranscriptReady("words."))

        val transition = machine.handle(BubbleEvent.Dismiss)

        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `long-press on result starts a fresh PTT recording immediately`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.TranscriptReady("words."))

        val transition = machine.handle(BubbleEvent.LongPressStart)

        assertEquals(BubbleState.Recording(pushToTalk = true), transition.state)
        assertEquals(listOf(BubbleEffect.StartRecording), transition.effects)
    }

    @Test
    fun `error on result returns to idle and surfaces error`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.TranscriptReady("words."))

        val transition = machine.handle(BubbleEvent.Error("polish failed"))

        assertEquals(BubbleState.Idle, transition.state)
        assertEquals(listOf<BubbleEffect>(BubbleEffect.ShowError("polish failed")), transition.effects)
    }

    @Test
    fun `consecutive transcripts replace the result state and re-emit copy`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = true }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.TranscriptReady("first."))

        val secondTranscript = machine.handle(BubbleEvent.TranscriptReady("second."))
        assertEquals(BubbleState.Result("second."), secondTranscript.state)
        assertEquals(
            listOf<BubbleEffect>(
                BubbleEffect.AutoCopy("second."),
                BubbleEffect.ShowCopiedToast,
            ),
            secondTranscript.effects,
        )
    }

    @Test
    fun `full happy path idle ptt processing result idle`() {
        val machine = BubbleStateMachine().apply { autoCopyEnabled = true }

        val start = machine.handle(BubbleEvent.LongPressStart)
        assertEquals(BubbleState.Recording(pushToTalk = true), start.state)

        val release = machine.handle(BubbleEvent.LongPressEnd)
        assertEquals(BubbleState.Processing, release.state)

        val ready = machine.handle(BubbleEvent.TranscriptReady("dictated text"))
        assertEquals(BubbleState.Result("dictated text"), ready.state)

        val dismiss = machine.handle(BubbleEvent.Dismiss)
        assertEquals(BubbleState.Idle, dismiss.state)
    }

    // --- Accessibility upgrade: InsertInPlace effect ---

    /**
     * Per ADR-0001, accessibility is a *pure upgrade*: when the user has
     * granted the service the result transition should emit
     * [BubbleEffect.InsertInPlace] in place of the standalone
     * [BubbleEffect.AutoCopy] / [BubbleEffect.ShowCopiedToast]. The caller
     * (the bubble overlay service) handles the actual writer call and the
     * fallback path — the machine itself stays pure.
     */
    @Test
    fun `transcript ready with accessibility enabled emits InsertInPlace and skips AutoCopy`() {
        val machine = BubbleStateMachine().apply {
            autoCopyEnabled = true
            accessibilityEnabled = true
        }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap) // -> Processing

        val transition = machine.handle(BubbleEvent.TranscriptReady("Polished words."))

        assertEquals(BubbleState.Result("Polished words."), transition.state)
        assertEquals(
            listOf<BubbleEffect>(BubbleEffect.InsertInPlace("Polished words.")),
            transition.effects,
        )
    }

    @Test
    fun `accessibility takes priority over autoCopy when both are enabled`() {
        val machine = BubbleStateMachine().apply {
            autoCopyEnabled = true
            accessibilityEnabled = true
        }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("upgrade path"))

        assertTrue(
            "AutoCopy must not fire when accessibility is on — caller routes " +
                "through the standalone-mode delivery only on writer fallback",
            transition.effects.none { it is BubbleEffect.AutoCopy },
        )
        assertTrue(
            "ShowCopiedToast must not fire when accessibility is on",
            transition.effects.none { it == BubbleEffect.ShowCopiedToast },
        )
    }

    @Test
    fun `accessibility disabled keeps the existing autoCopy path intact`() {
        val machine = BubbleStateMachine().apply {
            autoCopyEnabled = true
            accessibilityEnabled = false
        }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("standalone"))

        assertEquals(
            listOf<BubbleEffect>(
                BubbleEffect.AutoCopy("standalone"),
                BubbleEffect.ShowCopiedToast,
            ),
            transition.effects,
        )
    }

    @Test
    fun `blank transcript still goes idle silently with accessibility on`() {
        val machine = BubbleStateMachine().apply { accessibilityEnabled = true }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("   "))

        assertEquals(BubbleState.Idle, transition.state)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `accessibility disabled and autoCopy disabled emits no copy effects`() {
        val machine = BubbleStateMachine().apply {
            autoCopyEnabled = false
            accessibilityEnabled = false
        }
        machine.handle(BubbleEvent.Tap)
        machine.handle(BubbleEvent.Tap)

        val transition = machine.handle(BubbleEvent.TranscriptReady("manual only"))

        assertEquals(BubbleState.Result("manual only"), transition.state)
        assertTrue(
            "Both flags off must produce a Result state with no copy effects",
            transition.effects.isEmpty(),
        )
    }

    // --- Edge-sliver redesign: Dismissed and Peeked states ---

    @Test
    fun `idle plus DragTearComplete transitions to Dismissed`() {
        val machine = BubbleStateMachine()
        val transition = machine.handle(
            BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L)
        )
        val state = transition.state
        assertTrue("expected Dismissed, was $state", state is BubbleState.Dismissed)
        assertEquals(6_000L, (state as BubbleState.Dismissed).cooldownEndsAt)
    }

    @Test
    fun `recording ignores DragTearComplete`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.LongPressStart)
        val before = machine.state
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        assertEquals(before, machine.state)
    }

    @Test
    fun `processing ignores DragTearComplete`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.LongPressStart)
        machine.handle(BubbleEvent.LongPressEnd)
        val before = machine.state
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        assertEquals(before, machine.state)
    }

    @Test
    fun `dismissed plus CooldownElapsed transitions to Idle`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.DragTearComplete(now = 1_000L, cooldownMs = 5_000L))
        val transition = machine.handle(BubbleEvent.CooldownElapsed)
        assertTrue(transition.state is BubbleState.Idle)
    }

    @Test
    fun `idle plus Peek transitions to Peeked`() {
        val machine = BubbleStateMachine()
        val transition = machine.handle(BubbleEvent.Peek)
        assertTrue(transition.state is BubbleState.Peeked)
    }

    @Test
    fun `peeked plus PeekTimeout transitions to Idle`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Peek)
        val transition = machine.handle(BubbleEvent.PeekTimeout)
        assertTrue(transition.state is BubbleState.Idle)
    }

    @Test
    fun `peeked plus LongPressStart transitions to Recording`() {
        val machine = BubbleStateMachine()
        machine.handle(BubbleEvent.Peek)
        val transition = machine.handle(BubbleEvent.LongPressStart)
        assertTrue(transition.state is BubbleState.Recording)
    }
}
