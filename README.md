# Whisper Board

An Android speech-to-text keyboard powered by [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Runs OpenAI's Whisper model on-device via JNI for private, offline transcription — or connects to an API endpoint for server-side processing.

## Features

- **On-device transcription** — whisper.cpp runs locally via JNI, no data leaves your phone
- **API mode** — optionally route transcription to a remote Whisper-compatible API
- **Multi-language support** — select from Whisper's supported languages
- **Downloadable models** — choose model size (tiny, base, small, etc.) to balance speed vs. accuracy
- **Real-time waveform** — live audio visualization while recording
- **Jetpack Compose UI** — modern Material 3 keyboard and settings interface
- **Press-and-hold or toggle** — flexible recording interaction

## Requirements

- Android 8.0+ (API 26)
- JDK 17
- Android SDK 35, NDK 26.1
- CMake 3.22.1

## Build

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/dobrerares/whisper-board.git
cd whisper-board
```

### Standard build

```bash
./gradlew :app:assembleDebug
```

### NixOS

The Nix flake provides a complete dev environment:

```bash
nix develop
./gradlew :app:assembleDebug
```

The flake's `shellHook` generates `local.properties` with the correct `aapt2` override automatically.

### Faster iteration

```bash
# Kotlin-only compile (skips native + resources):
./gradlew :app:compileDebugKotlin
```

## Architecture

Two Gradle modules:

| Module | Role |
|--------|------|
| `:whisper` | JNI bridge to whisper.cpp (C++ via CMake, ABIs: arm64-v8a, armeabi-v7a, x86_64) |
| `:app` | Android InputMethodService + Compose UI |

### App structure

```
app/src/main/java/com/whisperboard/
├── WhisperBoardIME.kt          # IME service entry point
├── audio/                      # Mic recording pipeline
├── model/                      # Model management, language data, preferences
├── settings/                   # Settings activity & Compose screen
├── transcription/              # Engine abstraction (local + API)
└── ui/                         # Keyboard UI, view model, waveform
```

### How it works

1. `WhisperBoardIME` registers as an Android input method
2. User holds the mic button — `AudioPipeline` captures PCM audio
3. `EngineRouter` dispatches to either `LocalEngine` (on-device whisper.cpp) or `ApiEngine` (remote)
4. Transcribed text is committed to the active input field

### Native layer

The `:whisper` module compiles `third_party/whisper.cpp` (git submodule) via CMake and exposes it through JNI. Models are GGML-format files downloaded at runtime.

## License

TBD
