# Phase 3: Full Keyboard UI — Design

**Goal:** Flesh out the keyboard UI with waveform visualization, a complete language system (picker, favorites, IME subtypes), and fix Phase 2 deferred items.

**Approach:** Feature slices — build complete vertical features one at a time. Three slices: language system, waveform visualizer, deferred fixes.

---

## Current State

- 280dp keyboard layout: TranscriptionArea, LanguageChip (stub), MicButton, EditingKeysRow
- AudioPipeline emits `waveformData` every 100ms but nothing renders it
- LanguageChip onClick is a no-op placeholder
- `_activeLanguage` is a local `MutableStateFlow("auto")` in KeyboardViewModel — not persisted
- Model loads once in `WhisperBoardIME.onCreate()` — no hot-reload on selection change
- Errors written into `_transcribedText` (e.g., "[No model loaded]") instead of proper feedback
- No download cancellation, no delete confirmation for active model
- DataStore declared privately in `ModelRepository.kt` as `model_prefs`

---

## Slice 1: Language System

### 1a: Language Data

**New file:** `app/src/main/kotlin/com/whisperboard/model/WhisperLanguages.kt`

`object WhisperLanguages` with `val codes: Map<String, String>` — all ~99 Whisper language codes to display names (e.g., `"en" to "English"`). Hardcoded, stable, matches whisper.cpp's supported set.

### 1b: Shared DataStore

**New file:** `app/src/main/kotlin/com/whisperboard/model/AppPreferences.kt`

Top-level extension property: `val Context.appDataStore by preferencesDataStore(name = "app_prefs")`

**Modify:** `ModelRepository.kt` — remove private `preferencesDataStore` declaration, import `appDataStore` from `AppPreferences.kt`. Rename store from `model_prefs` to `app_prefs` (no migration needed, pre-release).

### 1c: Language Repository

**New file:** `app/src/main/kotlin/com/whisperboard/model/LanguageRepository.kt`

Uses shared `appDataStore`. API:
- `favoriteLanguages: Flow<Set<String>>` — set of language codes
- `addFavorite(code: String)` / `removeFavorite(code: String)`
- `activeLanguage: Flow<String>` — currently selected code (default "auto")
- `setActiveLanguage(code: String)`

### 1d: Language Picker Dialog

**New file:** `app/src/main/kotlin/com/whisperboard/ui/LanguagePickerDialog.kt`

AlertDialog with:
- Search TextField at top (filters by name or code)
- "Favorites" section — starred languages pinned to top with filled star toggle
- "All Languages" section — remaining languages with outlined star toggle
- Tapping a language selects it and dismisses
- Tapping the star toggles favorite without dismissing

**Modify:** `LanguageChip.kt` — add `onClick` callback and dialog state. Tapping the chip opens `LanguagePickerDialog`. On selection, calls `viewModel.setLanguage(code)`.

**Modify:** `KeyboardViewModel.kt`:
- Constructor takes `LanguageRepository` in addition to `AudioPipeline`
- `activeLanguage` reads from `LanguageRepository.activeLanguage` instead of local MutableStateFlow
- Add `setLanguage(code: String)` method
- Expose `favoriteLanguages` flow for the picker

### 1e: Settings Language Section

**Rename:** `ModelManagerScreen.kt` → `SettingsScreen.kt`

Add "Languages" section below "Models" in the LazyColumn:
- List of all languages with star toggles (same UX as picker)
- Alphabetical order

**Modify:** `SettingsActivity.kt` — create and pass `LanguageRepository` to `SettingsScreen`.

### 1f: IME Subtypes

**Modify:** `method.xml` — pre-register top ~25 languages as subtypes: en, zh, de, es, ru, ko, fr, ja, pt, tr, pl, ca, nl, ar, sv, it, id, hi, fi, vi, he, uk, el, ms, cs. Keep existing "Auto-detect" subtype.

**Modify:** `WhisperBoardIME.kt`:
- Read `favoriteLanguages` from `LanguageRepository` in `onCreate()`
- Call `InputMethodManager.setAdditionalInputMethodSubtypes()` to enable only favorites + auto-detect
- Override `onCurrentInputMethodSubtypeChanged(subtype)` — update `LanguageRepository.setActiveLanguage()` so chip and whisper.cpp stay in sync

---

## Slice 2: Waveform Visualizer

**New file:** `app/src/main/kotlin/com/whisperboard/ui/WaveformBar.kt`

Row of ~20-30 vertical bars animated with audio amplitude:
- Takes `FloatArray` from `KeyboardViewModel.waveformData`
- Downsamples to bar count
- Renders as `Canvas` with vertical rounded-rect bars
- Bars animate with `animateFloatAsState` (spring spec)
- Height: ~40dp, visible only while recording (collapse to 0dp otherwise)

**Modify:** `KeyboardView.kt`:
- Add `WaveformBar` between `MicButton` and `EditingKeysRow`
- Pass `waveformData` state
- Increase total height from 280dp to ~320dp

---

## Slice 3: Deferred Fixes

### 3a: Hot-reload model in IME

**Modify:** `WhisperBoardIME.kt`

Collect `ModelRepository.activeModelName` as a Flow in `onCreate()`. On each emission:
- Close old WhisperContext
- Load new model path
- Call `viewModel.setWhisperContext(newCtx)`
- If null (model deleted), set context to null

Use `collectLatest` so rapid switches cancel in-flight loads.

### 3b: Error feedback (Snackbar)

**Modify:** `KeyboardViewModel.kt` — add `SharedFlow<String>` for transient error/status messages. Replace current pattern of writing errors into `_transcribedText` (lines 68-69, 89-90, 94-95).

**Modify:** `KeyboardView.kt` — add `SnackbarHost` at bottom of keyboard. Collect error flow and display Snackbar.

### 3c: Delete confirmation for active model

**Modify:** `SettingsScreen.kt` — when deleting the active model, show AlertDialog: "This model is currently in use. Delete it anyway?" Cancel / Delete buttons. Non-active models delete immediately.

### 3d: Download cancellation

**Modify:** `ModelRepository.kt` — store OkHttp `Call` reference during download. Add `cancelDownload()` that calls `call.cancel()` and cleans up temp file.

**Modify:** `SettingsScreen.kt` — show "Cancel" button while downloading instead of just the spinner.

---

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Waveform style | Animated bar equalizer | Simple, low CPU, visually clear |
| Voice command switching | Deferred to Phase 5 | Two switching methods (chip + IME subtypes) sufficient; voice commands risk false positives |
| Favorites UI location | Both picker and settings | Discoverable inline + manageable in settings |
| IME subtype strategy | Pre-register ~25 in XML, enable/disable | Well-supported API, no OEM quirks |
| Phase 2 deferred items | Bundled into Phase 3 | Avoids re-touching same files |
| DataStore consolidation | Shared `app_prefs` via `AppPreferences.kt` | Singleton requirement — one DataStore per file name per process |

---

## Verification Checklist

### Language system
1. Tap language chip → picker opens with search and favorites
2. Search for "French" → filters correctly
3. Star a language → appears in favorites section
4. Select French → chip updates, transcribe in French → correct output
5. Open Settings → Languages section shows same favorites with stars
6. Star/unstar in Settings → reflected in keyboard picker
7. Switch language via Android IME switcher → chip and transcription language update

### Waveform
8. Tap mic → bar equalizer appears and animates with voice
9. Stop recording → bars collapse smoothly
10. While idle → no waveform visible

### Deferred fixes
11. In Settings, switch active model → IME hot-reloads without restart
12. Tap mic with no model → Snackbar shows error (not in transcription area)
13. Delete active model → confirmation dialog appears
14. Cancel active download → download stops, temp file cleaned up
