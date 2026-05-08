# Custom vocabulary feeds Whisper and the post-processor

A user-curated list of multi-word **vocabulary phrases** is held as a single global `Set<String>` and fed into two stages: (a) Whisper engines (`LocalEngine` via whisper.cpp's `initial_prompt`, `ApiEngine` via OpenAI's `prompt`) and (b) the **post-processor** via a new `customWordsSection` in `PromptBuilder`, parallel to the existing `languageProfileSection`. The Settings UI surfaces a coarse token-budget meter (`chars / 4`) bounded by Whisper's 224-token `initial_prompt` cap; precise BPE tokenization happens at prompt-build time and applies a newest-wins truncation when the cap is exceeded.

## Why this shape

`initial_prompt` is positional context, not a vocabulary lookup, so multi-word phrases ("OAuth flow", "Cluj-Napoca") preserve the sequential bias the model needs — single tokens (Handy's choice) lose this. Feeding the same list to the post-processor gives a second layer of defense: Whisper-side bias prevents most mishearings of proper nouns; the LLM stage corrects what slipped through. The two-stage shape is symmetric with how the **language profile** is fed unconditionally per ADR-0003 — same architectural pattern, same justification (marginal token cost, compounding user value).

## Scope creep — accepted

`PromptBuilder`'s contract previously stated "Tier 3+ is explicitly out of scope for v1." Adding `customWordsSection` extends Tier 2 to include vocabulary-anchored proper-noun spelling restoration. We accept this widening because (a) the mechanism already exists for parallel context (`languageProfileSection`), (b) the token cost is rounding-error, and (c) the alternative — Whisper-side bias only — wastes the post-processor's existing position in the pipeline. `PromptBuilderTest` should grow assertions on the new section to catch prompt drift the same way it does for the language-profile section.

## Considered and rejected

- **Per-language vocabulary list.** Doubles `language profile`'s sprawl for a different reason; interacts badly with the 224-token cap (per-language lists eat the same budget); requires a UI mode for managing per-language vocab. Single global list is sufficient for solo-project users with <50 phrases total.
- **Per-domain or per-app vocabulary.** Tier-4 complexity per ADR-0003's precedent; deferred indefinitely for the same reasons "per-app language profile" was rejected.
- **Single tokens only (Handy's behavior).** Rejected: phrases preserve positional context that single tokens lose. The `!sanitizedWord.includes(" ")` constraint Handy enforces is a UX inheritance from "tag input" mental models, not a Whisper API limit. Whisper accepts arbitrary `initial_prompt` text.
- **Whisper engines only — skip the post-processor.** Gives up the second defensive layer. When Whisper still mishears a proper noun (low audio quality, unusual phrasing), the post-processor has no signal to correct it.
- **Post-processor only — skip Whisper feeding.** Conservative on Whisper-side over-bias risk, but loses the win that biasing Whisper *prevents* the mishearing in the first place. First-pass quality matters even when polish is on, and matters more when polish is off.
- **Silent truncation without a budget meter.** Users add entries past the cap and get no signal that later entries are being dropped. The meter is ~25 lines of Compose — not worth saving.
- **Auto-suggest custom vocabulary from user corrections in dictation history.** Genuinely the right long-term direction (matches the progressive-disclosure pattern in `FirstLaunchPrompt` and the Bubble's lazy-unlock for accessibility insertion), but requires diff detection in `DictationHistory` and an interception hook in `TranscriptDelivery`. Parked as a v2 feature.
