# Curated Whisper model ladder: nine entries, mobile-realistic ceiling

`ModelManifest.models` lists nine curated GGML Whisper variants pulled from the canonical `ggerganov/whisper.cpp` Hugging Face mirror: `tiny`, `tiny.en`, `base`, `base.en`, `small`, `small.en`, `medium`, `medium.en`, and `large-v3-turbo`. The curated ceiling is `large-v3-turbo` at ~1.5 GB; full `large-v3` (~3 GB) is **not** in the curated list. Power users who want unsupported variants (`large-v3`, distilled models, quantized variants, non-Hugging-Face mirrors) self-serve via `ImportModelDialog`. Every curated entry must carry a non-empty `sha256` — the existing three entries have empty hashes today, and that gap is closed as part of this change rather than expanded.

## Why this curation

The picker is a triage surface, not an exhaustive catalogue. Three principles drove the cut:

1. **English-only variants for every multilingual variant we ship.** `.en` models are the same file size as their multilingual counterparts but noticeably more accurate for English speakers — a free quality win for the largest plausible user cohort. Withholding them only because Handy doesn't ship them imports a desktop curation choice that doesn't fit mobile's storage-per-user reality.
2. **Mobile-realistic ceiling at `large-v3-turbo` (~1.5 GB), not full `large-v3` (~3 GB).** `large-v3` is RAM-loadable on only the highest-tier Android devices and storage-painful for most. `large-v3-turbo` covers the "best quality I can actually run" case for the realistic mobile audience. Users with hardware to run full `large-v3` are also users who can sideload it through `ImportModelDialog` — the curated picker is not the right place to advertise a setting most users will hit memory pressure on.
3. **Skip quantized and distilled variants in v1.** `q5/q8 large-v3-turbo` and `distil-large-v3` are real mobile wins, but each one introduces a "what's the difference between *this* small-ish model and that small-ish model?" decision that the picker doesn't have a UX for today. Adding them is its own design discussion.

## sha256 verification — no longer optional

The existing three entries carry `sha256 = ""`. Adding six more entries with empty hashes would compound a known integrity gap: a compromised mirror could substitute models without detection. As part of expanding the manifest, all nine entries get their official Hugging Face hashes populated, and `ModelRepository`'s download path verifies the hash before activating a model. The verification path itself (existing or to-be-added in `ModelRepository`) is not specified by this ADR; the requirement is that empty `sha256` strings are no longer an acceptable manifest state.

## Considered and rejected

- **Minimal add: `.en` variants only, skip `medium` and `large-v3-turbo`.** Smaller change but leaves a quality gap between `small` (~466 MB) and the unbounded `ImportModelDialog` path. Users looking for "noticeably better than small" have to discover sideload, which is friction for a use case the curated picker should serve.
- **Handy's exact set (`small`, `medium`, `large-v3-turbo`, `large-v3`).** No `.en` variants and includes 3 GB `large-v3`. Different curation philosophy that fits Handy's desktop assumptions but not mobile's storage and RAM realities.
- **Full ladder including quantized and distilled variants.** Genuinely mobile-friendly options, but the picker UI doesn't yet differentiate "small q5_turbo" from "small.en" in a way users can choose between. Premature add.
- **Defer the expansion entirely; let `ImportModelDialog` cover everything.** `.en` variants are a strict quality win for English speakers — withholding them while the picker exists at all is the wrong default. `large-v3-turbo` is what users will look for after experiencing `small`'s ceiling. Punting both means the curated list is a permanent beginner-only surface.
- **Populate `sha256` in a follow-up task.** Possible but doubles the integrity gap before closing it; coupling the population to the expansion is the cleaner sequence.

## Follow-up implementation task

Edit `ModelManifest.kt` to add the six new entries. Hash population requires fetching the official sha256 from Hugging Face (each model has a `.json` sidecar in the mirror) or running `sha256sum` against a downloaded artifact. `ModelRepository`'s download verification — if not already present — is part of this implementation work; this ADR commits to it being part of the change but does not specify the implementation.
