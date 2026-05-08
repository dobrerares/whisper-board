# Whisper Board

Android speech-to-text app aiming to be a Wispr-Flow-class dictation tool: speak anywhere, get clean text wherever you need it.

## Language

### Surfaces

**Input surface**:
A way for the user to invoke transcription. Whisper Board ships *two* in parallel; they are independent.
_Avoid_: "entry point" (overloaded with the Android `Activity`/`Service` sense)

**IME**:
The `InputMethodService`-based keyboard. Active only when a text field is focused. Inserts text via `InputConnection.commitText` — needs no special permissions beyond `RECORD_AUDIO`.
_Avoid_: "the keyboard" when ambiguity with system keyboard matters

**Bubble**:
A floating overlay surface (`SYSTEM_ALERT_WINDOW`) that's available outside any text field. Independent of the IME — neither requires the other.
_Avoid_: "overlay" (the platform also calls notifications and dialogs overlays); "chat head"

### Bubble modes

**Standalone mode**:
Bubble works without an accessibility service. Records → shows the transcript → user copies or shares it. Permission cost: overlay only.

**In-place insertion**:
Bubble writes the transcript directly into the focused field via the accessibility service (`ACTION_SET_TEXT` / `ACTION_PASTE`). Permission cost: overlay + accessibility. Unlocks lazily after the user has used standalone mode.

_Avoid_: "auto-paste" (collides with clipboard auto-paste, which is a separate fallback technique)

### Capture

**Voice activity (VAD)**:
A binary classifier (Silero, ONNX) that scores each 30 ms frame of recorded audio for the presence of voice. Lives inside `AudioPipeline`. Has two purposes — **pre-trim** and **streaming auto-stop** — that share one runtime.
_Avoid_: "noise gate" (audio-production term, different semantics); "silence detection" (ambiguous between trim and auto-stop); "endpoint detection" (telephony jargon).

**Pre-trim**:
At `stopRecording()`, the captured buffer is cropped to drop leading and trailing silence frames; if no voice frames remain, transcription is skipped and the surface shows "No voice detected". Strict input-quality improvement — addresses Whisper's silence hallucinations and cuts inference time. Applies before *both* `LocalEngine` and `ApiEngine`. On by default; a master toggle in Settings → Audio is the escape hatch for whispered/sung/musical input.
_Avoid_: "silence trimming" alone (doesn't disambiguate from auto-stop).

**Streaming auto-stop**:
During recording, once at least one voice frame has been seen and N seconds of sustained silence follow, `AudioPipeline` self-terminates. **Surface-aware default**: off in the **IME** (preserves the press-and-hold contract on `MicButton`), on in the **Bubble** (no clean release gesture in `EdgeSliver` + `DragTear`). Silence duration is configurable (1–5 s, default 2 s).
_Avoid_: "auto-end", "VAD endpointing".

**Voice frame**:
The unit Silero processes — 480 samples at 16 kHz mono float32 (30 ms). `AudioPipeline` re-slices the `AudioRecord` read buffer into voice frames before feeding the VAD model.
_Avoid_: "VAD frame"; "audio frame" (ambiguous with the larger AudioRecord chunks).

### Vocabulary

**Custom vocabulary**:
A user-curated list of **vocabulary phrases** fed into both Whisper engines (as `initial_prompt` / OpenAI `prompt`) and the **post-processor** (as a system-prompt section, parallel to **language profile**). Globally scoped — one list per user, not per-language, per-domain, or per-app. Bounded by Whisper's **token budget**; Settings → Vocabulary surfaces a coarse meter.
_Avoid_: "custom words" (entries are multi-word phrases — "words" is misleading); "dictionary" (collides with mobile system dictionaries).

**Vocabulary phrase**:
A single entry in **custom vocabulary**. Free-form text including spaces (e.g., "OAuth flow", "Cluj-Napoca", "Steve Jobs"), max 50 chars. At prompt-build time phrases are joined with `, ` and truncated newest-wins when the **token budget** is exceeded.
_Avoid_: "term", "keyword".

**Token budget**:
Whisper's hard cap on `initial_prompt` length — 224 tokens. The Settings UI shows a coarse approximation (`chars / 4`) so users can prune their list before precise BPE tokenization at prompt-build time silently truncates them.
_Avoid_: "prompt budget" (ambiguous with the post-processor's separate prompt size).

### Post-processing

**Post-processor**:
A pipeline stage that runs *after* transcription, takes raw Whisper text and produces cleaned, formatted output (Tier 2: cleanup of fillers/false starts + list/paragraph detection). Mirrors `EngineRouter`'s shape — sealed interface, pluggable implementations, strategy-routed.

**Polish mode**:
Whether the post-processor runs. Auto-on by default; a master toggle in Settings disables it. There is no per-utterance polish/raw choice — that affordance was considered and explicitly rejected (ADR-0008). The unpolished transcript is still persisted on `DictationEntry` and surfaces in **dictation history** as an inspector view, so users can audit what Whisper actually heard without changing the default insertion flow.
_Avoid_: "cleanup mode", "LLM mode" (both ambiguous); "raw mode" (no such mode exists at the per-utterance level).

### Commit & history

**Auto-insert**:
The default behavior — the polished transcript is written into the focused field as soon as transcription completes (`InputConnection.commitText` in the IME, accessibility `ACTION_SET_TEXT` in the bubble's in-place insertion mode). Configurable; turning it off restores preview-then-commit, where the user reviews and taps to send.
_Avoid_: "auto-paste" (collides with clipboard auto-copy, which is a separate toggle)

**Auto-copy**:
The bubble's **standalone mode** equivalent of **auto-insert** — when there's no focused field to commit to, the polished transcript is auto-copied to the clipboard. Independent toggle from auto-insert.

**Auto-submit**:
A behaviour toggle (default off, **IME only**) that, after **auto-insert** writes the transcript into the focused field, sends the field's declared IME action — but *only* when `EditorInfo.imeOptions` carries `IME_ACTION_SEND`, `IME_ACTION_DONE`, or `IME_ACTION_GO`. In fields without one of those declared actions (multi-line notes, generic text inputs) the toggle is a deliberate no-op. The **Bubble**'s accessibility delivery has no `EditorInfo` and is unaffected.
_Avoid_: "auto-send" (collides with mail-app send semantics); "auto-enter" (suggests blunt `KEYCODE_ENTER`, which is exactly what this is *not*).

**Dictation history**:
A persistent stream of recent **dictation entries** (polished + raw transcript + timestamp + detected language(s) + target app name; *no audio*). Stored in Room. Default retention 100 entries; configurable. Surfaces in two places: the IME's transcript area (when **auto-insert** is on, it becomes a scroll of recent entries, each tap-to-reinsert), and Settings → History.

**Dictation entry**:
A single item in **dictation history** — one utterance's worth of transcript. The unit of "tap to re-insert" and "swipe to delete".

### Languages

**Language profile / Spoken languages**:
The set of languages a user has declared they speak. Used by the post-processor (system prompt context, restoring code-switched phrases) and by the UI (model picker hints, chip menu scoping). Default for new users: declared at first launch via a one-screen prompt; skippable, in which case the profile defaults to `[auto]`.

**Active language**:
The language to use for the next utterance. Defaults to "auto" (Whisper detects); user can pin to a specific language via the chip. Independent of the profile — pinning doesn't change the profile.

**Code-switching**:
Speaking multiple languages within a single utterance. whisper.cpp transcribes with `language=auto`; recovery of phonetically-mangled phrases happens in the post-processor stage using the profile as context. Quality is meaningfully better on whisper-large-v3 than on smaller models.

**Translate-to-English mode**:
A global toggle in Settings → Transcription that switches Whisper's task from `transcribe` to `translate`. When on, every utterance is rendered as English regardless of source language — `LocalEngine` sets `whisper_full_params.translate = true`; `ApiEngine` calls `/v1/audio/translations` instead of `/v1/audio/transcriptions`. Off by default. Independent of **active language** — pinning a non-English language with translate on means "force-detect this language, then translate to English"; pinning English makes the toggle a no-op. **Polish mode** still runs (cleans the translated English). **Custom vocabulary** rides into Whisper's `initial_prompt` (helps recognition of proper nouns at translation time) but is *omitted* from the post-processor's system prompt while translating, since vocabulary phrases no longer appear in their original form in the translated English and a vocabulary-anchored post-processor would over-correct.
_Avoid_: "translate mode" alone (ambiguous — only the English target is supported); "translation" (collides with the deferred S3 scope per ADR-0003).

## Relationships

- A user installs **Whisper Board** once and gets the **IME** and the **Bubble** as independently enableable surfaces
- The **Bubble** runs in **standalone mode** by default; granting the accessibility service upgrades it to **in-place insertion**
- Both surfaces feed the same `EngineRouter` — surface choice is decoupled from transcription
- Captured audio passes through **pre-trim** before reaching `EngineRouter`; trim applies equally to **LocalEngine** and **ApiEngine** paths
- **Streaming auto-stop**, when enabled for the active surface, terminates capture inside `AudioPipeline` before `EngineRouter` is invoked
- Output of `EngineRouter` flows through the **Post-processor** before reaching the user, governed by **Polish mode**
- **Custom vocabulary** is fed unconditionally into both Whisper engines via `initial_prompt`/`prompt` *and* into the **Post-processor** system prompt (parallel mechanism to the **Language profile**) — *except* when **translate-to-English mode** is on, where vocabulary feeds Whisper only
- **Translate-to-English mode** flips Whisper's task from `transcribe` to `translate`; **active language** still drives the source-language detection; **polish mode** runs over the translated English the same way it runs over native English
- The **Language profile** is read-only context for the **Post-processor**; transcription itself uses **Active language**

## Example dialogue

> **Dev:** "When the user dictates while a text field is focused, do they go through the IME?"
> **Owner:** "Not necessarily. If they enabled the **Bubble**, that's an independent **input surface**. The IME and Bubble are parallel — neither requires the other."
>
> **Dev:** "And the Bubble writes into the field directly?"
> **Owner:** "Only if **in-place insertion** is on, which needs the accessibility service. Without it, the Bubble is in **standalone mode** — shows the transcript and **auto-copies** to the clipboard."
>
> **Dev:** "What if I speak Romanian and English in the same sentence?"
> **Owner:** "That's **code-switching**. Whisper runs with `language=auto` and detects per utterance. Recovery of mangled phrases happens in the **post-processor** using the **language profile** as context — that's why the profile is unconditionally passed to the LLM, even for monolingual users."
>
> **Dev:** "So the profile is also passed to Whisper?"
> **Owner:** "No. whisper.cpp can't accept a *set* of languages; only a single one or `auto`. The profile is a post-processor input, not a transcription input. ADR-0003 spells this out."
>
> **Dev:** "If polish is off, what happens?"
> **Owner:** "The post-processor is bypassed entirely. Raw Whisper text goes through. **Auto-insert** still operates; the user sees raw text in their field."
>
> **Dev:** "If the user holds the mic but never says anything, what gets sent to Whisper?"
> **Owner:** "Nothing. **Pre-trim** crops the buffer to **voice frames**; with zero voice frames, transcription is skipped and the surface shows 'No voice detected'. Whisper's silence hallucinations ('Thank you for watching') are the whole reason **voice activity** is in the pipeline."
>
> **Dev:** "If I add 'Kafka' to **custom vocabulary** and dictate 'Kafco', what happens?"
> **Owner:** "Two layers of defense. Whisper's `initial_prompt` is biased toward 'Kafka', so you might get 'Kafka' in the raw transcript directly. If Whisper still hears 'Kafco', the **post-processor** has the same vocabulary in its system prompt and corrects the spelling there. **Custom vocabulary** is fed to both — defense in depth, same shape as how the **language profile** is fed unconditionally per ADR-0003."

## Flagged ambiguities

- "Accessibility-based bubble" was used as a single phrase in early discussion. Resolved: the **Bubble** is an overlay decision; the **accessibility service** is an independent permission decision that gates **in-place insertion** but not the bubble itself.
- "Multilingual support" could mean S1 (context switch), S2 (code-switching), S3 (translation), or S4 (light secondary). Resolved for v1: S1 + S2 in scope; S3 deferred but earmarked; S4 implicitly handled by S1's auto-detect.
