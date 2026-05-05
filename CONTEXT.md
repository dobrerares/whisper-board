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

### Post-processing

**Post-processor**:
A pipeline stage that runs *after* transcription, takes raw Whisper text and produces cleaned, formatted output (Tier 2: cleanup of fillers/false starts + list/paragraph detection). Mirrors `EngineRouter`'s shape — sealed interface, pluggable implementations, strategy-routed.

**Polish mode**:
Whether the post-processor runs. Auto-on by default; a master toggle in settings disables it; a per-utterance "show raw" affordance reveals the unpolished transcript without changing the default flow.
_Avoid_: "cleanup mode", "LLM mode" (both ambiguous).

### Commit & history

**Auto-insert**:
The default behavior — the polished transcript is written into the focused field as soon as transcription completes (`InputConnection.commitText` in the IME, accessibility `ACTION_SET_TEXT` in the bubble's in-place insertion mode). Configurable; turning it off restores preview-then-commit, where the user reviews and taps to send.
_Avoid_: "auto-paste" (collides with clipboard auto-copy, which is a separate toggle)

**Auto-copy**:
The bubble's **standalone mode** equivalent of **auto-insert** — when there's no focused field to commit to, the polished transcript is auto-copied to the clipboard. Independent toggle from auto-insert.

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

## Relationships

- A user installs **Whisper Board** once and gets the **IME** and the **Bubble** as independently enableable surfaces
- The **Bubble** runs in **standalone mode** by default; granting the accessibility service upgrades it to **in-place insertion**
- Both surfaces feed the same `EngineRouter` — surface choice is decoupled from transcription
- Output of `EngineRouter` flows through the **Post-processor** before reaching the user, governed by **Polish mode**
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

## Flagged ambiguities

- "Accessibility-based bubble" was used as a single phrase in early discussion. Resolved: the **Bubble** is an overlay decision; the **accessibility service** is an independent permission decision that gates **in-place insertion** but not the bubble itself.
- "Multilingual support" could mean S1 (context switch), S2 (code-switching), S3 (translation), or S4 (light secondary). Resolved for v1: S1 + S2 in scope; S3 deferred but earmarked; S4 implicitly handled by S1's auto-detect.
