# No audio start/stop feedback — haptics serve the same role

Whisper Board ships no audio start/stop tones. Recording-state feedback is delivered through haptics: `HapticFeedbackType.LongPress` on PTT activation and `HapticFeedbackType.TextHandleMove` on stop, already wired on `MicButton` in the IME. The Bubble surface has the same feedback need but is **not** haptically wired today; that is a deliberate follow-up task, not part of this ADR's scope.

## Why no audio

Two reasons make audio start/stop tones the wrong feedback channel on Android. First, audio playback during a recording can leak into the mic via the device speaker — even brief tones can show up in the captured buffer and degrade transcription quality, especially with `STREAM_MEDIA`-stream playback that bypasses Do Not Disturb. Second, mobile already has haptics — a feedback channel desktop Handy doesn't have access to — that work in pocket, don't leak into the mic, and don't conflict with the user's media playback. Audio sounds aren't a feature Handy *adds*; they're Handy's *only* option in a context where haptics aren't available. Translating the feature directly would import desktop-shaped trade-offs to a phone where the better answer is already on the device.

## Follow-up: haptics on the Bubble

The IME's `MicButton` covers IME-side feedback. The Bubble's `BubbleStateMachine`, `BubbleTileService`-driven recording starts, and VAD-driven streaming auto-stop transitions all currently produce no haptic feedback. Closing that gap (a `Vibrator` call on state transitions, or `view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)`) is the natural completion of "haptics are the feedback channel" — but it's a separate, smaller task and lives outside this rejection of audio.

## Considered and rejected

- **Bundled start/stop sounds, default off, Bubble-only.** Closes the Bubble feedback gap, but trades it for the mic-leakage failure mode. Haptics don't have that downside and are a more native mobile primitive.
- **Full sound-theme picker (Handy parity).** Settings sprawl for a feature Android already has a better channel for. The mic-leakage and DND-interaction trade-offs apply at any volume.
- **`STREAM_NOTIFICATION` (silenced by DND) for the cue.** Solves the DND case but not the mic-leakage case; the speaker still produces sound that the mic still captures.
