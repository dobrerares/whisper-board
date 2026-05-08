# Translate-to-English uses Whisper's native task; LLM-routed translation stays deferred

A global "Translate to English" toggle in Settings → Transcription flips Whisper's task from `transcribe` to `translate` for both engines (`LocalEngine` sets `whisper_full_params.translate = true`; `ApiEngine` switches endpoint from `/v1/audio/transcriptions` to `/v1/audio/translations`). Off by default. **Polish mode** still runs over the translated English. **Custom vocabulary** continues to feed Whisper's `initial_prompt` while translating, but is *omitted* from the post-processor's system prompt during a translate request — vocabulary phrases no longer appear in their original form in translated English, and a vocabulary-anchored polisher would over-correct.

## Relationship to ADR-0003

ADR-0003 deferred "S3 translation" but explicitly flagged the carve-out: "Whisper's built-in `translate` task is English-only; non-English targets would require routing translation through the LLM stage." This ADR picks up exactly that English-only slice. ADR-0003's deferral of *non-English-target* translation (the LLM-routed flavor) remains in force; this ADR does not reopen it.

## Why this shape

Translate-to-English is essentially free — one whisper.cpp parameter, one OpenAI endpoint switch — and lives entirely inside the existing **transcription stage** without creeping into the post-processor's scope. Routing translation through the LLM (the deferred S3 path) is fundamentally different work: it would make the post-processor language-target-aware, expand prompt complexity, and conflict with the **language profile**'s "preserve user's words" contract. Keeping the two paths separate preserves ADR-0003's tier discipline.

## UI placement — global toggle, not per-utterance

A per-utterance chip alongside the **language chip** was rejected: it competes for IME bar real estate that the language chip already owns, and translation is niche enough for most users that a global toggle in Settings is the right discoverability/clutter trade. A surface-aware default (analogous to **streaming auto-stop**'s IME-vs-Bubble split) was also considered and rejected — there's no surface-specific reason translation behavior should differ between the IME and Bubble.

## Considered and rejected

- **Per-utterance chip in IME / Bubble.** Crowds the language-chip area; most users don't translate. Defer until a use case shows up.
- **Surface-aware default (off in IME, on in Bubble or vice versa).** No surface-specific motivation; the auto-stop split was justified by gesture differences, none apply here.
- **Skip Polish mode while translating.** Considered as "translated English is already model-cleaned." Rejected: Whisper's translation output has the same filler/false-start/punctuation issues as direct transcription; polish helps the same way.
- **Feed custom vocabulary to the post-processor while translating.** The post-processor sees translated English text where vocabulary phrases ("Cluj-Napoca", "OAuth flow") may not appear in their original form — feeding them as "preserve these spellings" context produces over-corrections. Skipping the vocab in the post-processor system prompt while translating is the cleaner contract.
- **Auto-detect "user wanted to translate" from a per-utterance gesture (e.g., long-press mic).** Conflicts with the per-utterance polish/raw affordance still being designed in slot #5 of this session; the mic gesture vocabulary shouldn't be over-allocated before that decision is made.
