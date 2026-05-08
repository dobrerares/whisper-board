# Voice activity detection lives in `AudioPipeline`, runs Silero on ONNX Runtime

`AudioPipeline` owns a Silero VAD instance and runs it over each 30 ms voice frame as audio is captured. The pipeline supports two modes that share one runtime — **pre-trim** (always available; master toggle defaults on) and **streaming auto-stop** (surface-aware default: off in IME, on in Bubble). VAD output applies to both `LocalEngine` and `ApiEngine` paths; the engine receives an already-trimmed buffer. The `silero_vad.onnx` model (~2 MB) is bundled in `app/src/main/assets/`; the runtime is `onnxruntime-android` from Maven Central.

## Why this shape

Whisper hallucinates on low-energy non-silence ("Thank you", "[BLANK_AUDIO]", "Subscribe to my channel") — the cases where energy-threshold or WebRTC VADs misclassify and let bad audio through to Whisper. Silero's accuracy on these cases is the entire point of adding VAD. Co-locating VAD inside `AudioPipeline` avoids re-slicing the chunk stream into 30 ms frames twice and gives one owner for the runtime lifecycle, matching `AudioRecord`'s. Pre-trim applies to both engines because Whisper-the-model hallucinates the same way regardless of where it runs, and trimming reduces API request bytes and cost as a side benefit.

## Considered and rejected

- **Whisper.cpp's built-in `--vad` flag.** Would handle pre-trim "for free" via the existing JNI binding, but only fires inside the `whisper_full` call — can't drive streaming auto-stop, which needs frame-level signal *during* recording. Maintaining two VAD code paths (whisper.cpp's for pre-trim, Silero for auto-stop) was rejected as more brittle than one runtime serving both.
- **WebRTC VAD via NDK.** ~50 KB and matches the existing CMake flow, but its energy+spectral algorithm misclassifies the exact low-energy non-silence cases that motivate VAD. The accuracy delta isn't cosmetic.
- **Pure-Kotlin energy threshold.** Fails on the cases that matter; useful only as a placeholder.
- **VAD as a separate `VadStage` downstream of `AudioPipeline`.** Would require `AudioPipeline` to expose a `Flow<ShortArray>` of chunks rather than just a final `FloatArray` — broader API change than centralizing VAD inside the pipeline, and offers no compensating benefit.
- **Downloading Silero via `ModelRepository`.** Would save ~2 MB of APK at the cost of a first-run network failure mode for invisible plumbing. The size is below the noise floor and doesn't justify the failure mode. `ModelRepository` stays focused on user-facing model choice.
- **Default streaming auto-stop on for the IME.** Breaks the established press-and-hold contract on `MicButton` — users who hold the mic while pausing mid-thought would experience "the app cut me off". Surface-aware default (off in IME, on in Bubble) preserves the IME contract and lets the Bubble use auto-stop where its drag-tear gesture has no clean release.
- **Silent no-op when pre-trim removes all voice frames.** Considered for minimum-interruption Bubble UX. Rejected: surface-appropriate "No voice detected" feedback prevents the user from wondering whether the mic worked at all.
- **Skip pre-trim on the API path.** Considered as "if the user picked API, send everything raw." Rejected: the API runs the same Whisper, hallucinates the same way, charges per second of audio uploaded, and VAD trimming isn't lossy on voice — it removes silence frames only.
