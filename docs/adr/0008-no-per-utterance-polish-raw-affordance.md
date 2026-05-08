# No per-utterance polish/raw affordance

The user's polish/raw choice is global — it lives in `PostProcessingSettingsRepository.polishModeEnabled` (default on) and applies to every utterance from both surfaces. There is no pre-utterance gesture, chip, or shortcut for picking raw vs polished, and there is no post-utterance "show raw" reveal in the IME's `TranscriptionArea` or the Bubble's `SummonedSheet`. Raw and polished transcripts continue to be stored together on `DictationEntry`, but only as an inspector view in `DictationHistoryView` — not as a per-utterance switch on the active utterance.

## Why retract a previously-documented intent

An earlier `CONTEXT.md` defined **Polish mode** as including "a per-utterance 'show raw' affordance [that] reveals the unpolished transcript without changing the default flow." That clause is rescinded by this ADR.

The rationale was: a per-utterance choice is design speculation. The global toggle already covers users who always want polish and users who never want it. The remaining cohort — users who sometimes want polish and sometimes don't *for the same dictation* — has no signal to reach us today (no friction reports, no usage data, no explicit asks). Building the affordance ahead of that signal commits gesture vocabulary, IME header space, or `SummonedSheet` real estate to a use case we can't size. The Handy comparison reinforces this: Handy's two-shortcut model exists because Handy ships with global hotkeys where adding a second shortcut is free; whisper-board's mic gestures (`MicButton`) are already saturated (short-tap = toggle, long-press = PTT), and the language chip already owns the per-utterance chip slot.

## Considered and rejected

- **Pre-utterance via gesture (Handy's model).** Repurposing mic gestures (e.g., double-tap = raw) breaks the established `MicButton` vocabulary and adds cognitive load to the IME's quick-dictate hot path. Handy's model fits Handy's surface (global hotkeys with cheap second-shortcut binding); it doesn't fit whisper-board's surface.
- **Pre-utterance via "raw" chip in the IME header.** Doesn't conflict with mic gestures, but competes with the existing `LanguageChip` for visual attention and adds a decision before every dictation that most users won't have signal to make.
- **Post-utterance reveal in `SummonedSheet` and `TranscriptionArea`.** The original CONTEXT.md intent. Lower interaction cost than pre-utterance, but still spends design budget building UI for a use case without evidence. Inspector view in `DictationHistoryView` covers the audit case (the most common reason users would want to see raw text) without per-utterance UI clutter.
- **Surface-aware split (IME chip + Bubble post-utterance reveal).** Most theoretically pure, but creates two mental models for the same operation across surfaces. Inconsistency cost outweighs the per-surface fit.

## Reopening criteria

Reopen this decision if any of: (1) friction reports indicate users routinely want raw on specific dictation types, (2) a use case emerges where polish is *systematically* wrong (e.g., transcribing code, where polish over-corrects identifiers), or (3) the **language chip** is removed or repurposed and the per-utterance chip slot frees up.
