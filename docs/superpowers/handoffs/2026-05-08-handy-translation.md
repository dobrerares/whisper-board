# Handoff — Handy → Whisper Board feature translation grilling

**Session date:** 2026-05-08
**Scope:** Audit Handy (cjpais/handy desktop STT) for features worth porting to Whisper Board (Android STT keyboard); resolve design questions; produce a PRD.

## Outcome

- **PRD published as GitHub issue [#17](https://github.com/dobrerares/whisper-board/issues/17)** with `needs-triage` label. Run `/to-issues` against it to break into 8 implementation slices.
- **8 ADRs written** (0004–0011), one per resolved feature.
- **`CONTEXT.md` extended** with new "Capture" and "Vocabulary" sections, new "Translate-to-English mode" + "Auto-submit" entries, updated "Polish mode" entry retracting the per-utterance reveal language, plus two new dialogue exchanges.
- **No implementation code written.** All work is design / docs.

## Decisions

| ADR | Feature | One-line outcome |
|---|---|---|
| 0004 | Silero VAD | Both pre-trim + streaming auto-stop; ORT Android; bundled ONNX (~2 MB); surface-aware default (off in IME, on in Bubble); trims both engine paths |
| 0005 | Custom vocabulary | Single global list; multi-word phrases allowed; fed to both Whisper *and* post-processor; settings list + token-budget meter |
| 0006 | Translate-to-English | Global toggle, off by default; polish runs on translated English; vocab feeds Whisper but skips post-processor while translating |
| 0007 | Whisper idle unload | Idle-only (5 min default), no `Service.onTrimMemory` hook; "Never" option in Settings |
| 0008 | Per-utterance polish/raw | **Rejected**; CONTEXT.md "show raw" intent retracted; raw still stored on `DictationEntry` for inspector view |
| 0009 | Trailing space + auto-submit | Trailing space toggle (default on); auto-submit toggle (default off) gated on `imeAction ∈ {SEND, DONE, GO}`; no `KEYCODE_ENTER` fallback |
| 0010 | Audio start/stop sounds | **Rejected**; haptics serve the role; Bubble haptics is a deferred follow-up (currently zero haptic calls in `bubble/`) |
| 0011 | Curated model ladder | 9 entries (`tiny`/`tiny.en`/`base`/`base.en`/`small`/`small.en`/`medium`/`medium.en`/`large-v3-turbo`); `sha256` populated |

## Pending implementation work

Independent of each other; can be done in any order though VAD is the recommended starting point.

### Deep modules to build
- **`Vad` interface + `SileroVad`** — owned by `AudioPipeline`, ORT Android backend
- **`TokenBudgetEstimator`** — pure function, JVM-testable
- **`IdleUnloadingEngine`** — wraps a `LocalEngine` factory + idle timer
- **`VocabularyRepository`** — DataStore-backed `Set<String>`

### Existing modules to extend
- `AudioPipeline` — VAD frame slicing in read loop; pre-trim at `stopRecording()`; streaming auto-stop
- `WhisperContext` (JNI) + `LocalEngine` + `ApiEngine` — add `initialPrompt: String?` and `translate: Boolean` to the transcribe surface
- `PromptBuilder` + `PromptBuilderTest` — new `customWordsSection`; new `translateMode` parameter
- `BehaviorSettingsRepository` — 8 new settings keys (full list in PRD)
- `TranscriptDelivery` — append-trailing-space transformation; "no voice detected" signal
- `WhisperBoardIME` — `performEditorAction` after `commitText` when auto-submit fires + imeAction in {SEND, DONE, GO}
- `ModelManifest` — extend to 9 entries + populate `sha256`
- `ModelRepository` — sha256 verification on download (verify if path already exists)
- Bubble (`BubbleStateMachine`/`BubbleView`/`BubbleTileService`) — haptics on state transitions

### Settings UI additions (`SettingsScreen`)
- VAD section (master pre-trim + per-surface auto-stop + silence-duration slider)
- Vocabulary page (entry list + token-budget meter)
- Translate-to-English toggle
- Idle-unload duration slider with "Never"
- Trailing-space toggle, auto-submit toggle

## Recommended next session

Start with **VAD (ADR-0004)** because:
- Foundational — reshapes `AudioPipeline`'s output contract
- Highest user-visible quality lift (kills Whisper's silence hallucinations)
- Most-spec'd ADR with the cleanest deep-module extraction
- Unblocks the "no voice detected" signal that several other surfaces want

Suggested entry: read ADR-0004, sketch a slice plan covering ORT dependency add, asset bundling, `Vad` interface design, `AudioPipeline` integration, settings wiring, and `Vad`-only unit tests.

## Out of scope (do not reopen without new signal)

- **Parakeet V3** as a second local engine — parked; needs Android runtime research
- Per-utterance polish/raw chip or gesture — rejected per ADR-0008
- Audio start/stop sounds, sound theme picker — rejected per ADR-0010
- `Service.onTrimMemory` participation — deferred per ADR-0007
- Auto-suggest custom vocabulary from history corrections — deferred per ADR-0005
- Translation to non-English targets — deferred per ADR-0003 + 0006
- `KEYCODE_ENTER` fallback in non-imeAction fields — rejected per ADR-0009
- Quantized / distilled model variants in curated picker — deferred per ADR-0011
- Full `large-v3` (~3 GB) in curated picker — sideload-only per ADR-0011

## Files in this commit

- `CONTEXT.md` — modified (Capture + Vocabulary sections, Translate-to-English entry, Auto-submit entry, Polish mode rewrite, relationships, dialogues)
- `docs/adr/0004-vad-via-silero-onnx-in-audiopipeline.md` — new
- `docs/adr/0005-custom-vocabulary-fed-to-whisper-and-post-processor.md` — new
- `docs/adr/0006-translate-to-english-uses-whisper-native-task.md` — new
- `docs/adr/0007-whisper-model-idle-unload.md` — new
- `docs/adr/0008-no-per-utterance-polish-raw-affordance.md` — new
- `docs/adr/0009-auto-submit-gated-on-ime-action.md` — new
- `docs/adr/0010-no-audio-start-stop-feedback-haptics-instead.md` — new
- `docs/adr/0011-curated-whisper-model-ladder.md` — new
- `docs/superpowers/handoffs/2026-05-08-handy-translation.md` — this file
