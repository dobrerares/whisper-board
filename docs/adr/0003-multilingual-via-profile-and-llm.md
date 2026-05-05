# Multilingual via language profile + post-processor context

Multilingual handling lives in two places: a user-declared `spokenLanguages: Set<String>` *language profile* on `LanguageRepository`, and the post-processor's system prompt. The profile is **not** passed to whisper.cpp as a constraint — Whisper continues to receive `language=auto` (or a user-pinned override via the chip) and detects freely per utterance. The profile is consumed by the post-processor as system-prompt context: "the user speaks {languages}; if a phrase appears phonetically transcribed (i.e., one language transcribed using another language's spelling), restore it to its native language." This is where code-switching quality is recovered. The profile is set via a one-screen first-launch prompt (skippable; default `[auto]` preserves current behavior).

## Why this shape

whisper.cpp's `language` parameter is a single string — there is no native way to pass a *set* of allowed languages. Workarounds (per-segment detection, force-then-fallback, multiple passes) produce worse quality than letting Whisper run with `language=auto` on whisper-large-v3, which is a genuinely strong multilingual model. Code-switching recovery happens cheaply at the post-processor stage *because we are already running an LLM there for cleanup* — the profile is therefore architecturally a post-processor input, not a transcription input. Passing the profile to the LLM unconditionally (even for monolingual users) is a token-cost rounding error and produces a useful side effect: the LLM gets proper-noun spelling hints for the user's languages.

## Considered and rejected

- **Profile narrows Whisper's allowed languages.** Not directly supported by whisper.cpp; faking it via per-segment detection or force-then-fallback degrades quality below the `language=auto` baseline.
- **No profile, rely entirely on per-utterance auto-detect.** Misses the post-processor recovery win for code-switched users — the LLM has no signal about which languages a phonetic artifact might really be, so it can't reliably restore them.
- **Translation to a target language as part of v1 multilingual scope (S3).** Deferred. Whisper's built-in `translate` task is English-only; non-English targets would require routing translation through the LLM stage, which creeps Tier 2 scope into Tier 5 territory. Revisit when the user feels it's needed.
- **Per-app language profile (different profile per focused app).** Requires accessibility, requires inferring app context, requires a second-level data model. Tier-4-class complexity; deferred indefinitely.
