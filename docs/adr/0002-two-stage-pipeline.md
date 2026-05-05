# Two-stage pipeline with parallel routers

Transcription and post-processing are independent stages. Transcription continues to be routed by the existing `EngineRouter` (LocalEngine = whisper.cpp, ApiEngine = OpenAI-compatible `/v1/audio/transcriptions`). A new `PostProcessingRouter` mirrors its shape for the post-processing stage: a sealed `PostProcessor` interface, with `LocalPostProcessor` (llama.cpp + a small instruction-tuned model in the 1–3B range, e.g. Gemma 3 1B) and `ApiPostProcessor` (OpenAI-compatible `/v1/chat/completions`) implementations, governed by a `PostProcessingStrategy` enum that mirrors `EngineStrategy` (LOCAL_ONLY / API_ONLY / LOCAL_PREFERRED / API_WHEN_ONLINE / OFF). The post-processor runs Tier 2 by default (filler/false-start cleanup + list/paragraph formatting); a master toggle disables it entirely.

## Why this shape

The existing `EngineRouter` already proves the sealed-interface-plus-strategy pattern in this codebase. Mirroring it for post-processing means anyone who understands one understands the other — same vocabulary, same routing logic, same configuration surface. Shipping both runtimes in v1 (rather than remote-only first) preserves coherence with the existing offline pitch: if a user picks `LOCAL_ONLY` transcription, a remote-only post-processor would silently break offline mode by requiring internet for the polish step. Two stages, each with a local + remote option, lets the user mix-and-match (local Whisper + remote LLM is a coherent configuration, as is fully-local-fully-offline).

## Considered and rejected

- **Single combined engine that does transcription + cleanup in one call.** Some providers offer audio-in / structured-text-out via chat-completion APIs; whisper.cpp does not. Coupling the stages would force "if you self-host Whisper, you must also self-host the LLM" and remove the ability to mix providers.
- **Remote-only post-processor in v1, local SLM in v2.** Smaller v1 scope and faster ship, but fails the offline-coherence test: `LOCAL_ONLY` users would see post-processing silently disabled when offline. The user explicitly asked for both runtimes in v1 to avoid this.
- **Tier 1 only (cleanup, no formatting) in v1.** Cheaper prompt, smaller model fits, but Whisper itself already does most of Tier 1's work (basic punctuation, capitalization). Tier 2 is the credibility threshold where the LLM stage actually feels different from raw Whisper output.
