# Whisper Board

Android STT (speech-to-text) keyboard using whisper.cpp via JNI.

## Build

```bash
# Inside nix devshell (required on NixOS):
nix develop --command bash -c './gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2'

# Or if ANDROID_HOME is available directly:
./gradlew :app:assembleDebug

# Kotlin compile only (faster iteration):
./gradlew :app:compileDebugKotlin
```

## Architecture

Two Gradle modules:
- `:whisper` — JNI bridge to whisper.cpp (C++ via CMake, 3 ABIs: arm64-v8a, armeabi-v7a, x86_64)
- `:app` — Android InputMethodService + Compose UI

Key packages in `:app`:
- `audio/` — AudioPipeline (mic recording)
- `model/` — ModelInfo, ModelManifest, ModelRepository, LanguageRepository, WhisperLanguages, AppPreferences
- `settings/` — SettingsActivity + SettingsScreen (Compose)
- `transcription/` — TranscriptionEngine, LocalEngine, ApiEngine, EngineRouter, ApiSettingsRepository, WavEncoder
- `ui/` — KeyboardScreen, KeyboardViewModel, LanguagePickerDialog, WaveformBar
- `WhisperBoardIME.kt` — IME service entry point

Native code: `third_party/whisper.cpp` (git submodule)

## Gotchas

- **NixOS aapt2**: Maven-downloaded aapt2 is dynamically linked and fails. Always pass `-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`
- **Submodules after branch operations**: Run `git submodule update --init --recursive` after checkout/merge/worktree creation
- **Worktree removal with submodules**: `git worktree remove` fails — use `rm -rf <path> && git worktree prune`
- **DataStore singleton**: `preferencesDataStore` must be a top-level extension property (one per file name per process). Always pass `applicationContext`, never activity context
- **Compose `pointerInput` vs `clickable`**: Never put `pointerInput` on a parent wrapping a `FloatingActionButton` or `Surface(onClick=...)` — the child's click handler steals events. Use a plain `Surface` (no onClick) with `pointerInput` directly
- **Compose restricted coroutine scope**: `AwaitPointerEventScope` (inside `awaitEachGesture`) can't launch coroutines. Use `rememberCoroutineScope()` + `try/finally` for cancellation
- **Android ActionBar**: App has no AppCompat dependency. XML theme is `android:Theme.Material.Light.NoActionBar` in `res/values/themes.xml`; all real theming is via Compose `WhisperBoardTheme`
- **Material3 Compose has no XML theme resources**: `Theme.Material3.*` styles don't exist as XML resources — use `android:Theme.Material.*` as the XML base

## Git

Solo project — commit and push directly to main.
