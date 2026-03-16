# UI Overhaul Design

**Goal:** Fix dark theme, improve keyboard aesthetics, add push-to-talk, fix language picker, add key repeat.

**Architecture:** All changes are in Compose UI layer. New `WhisperBoardTheme` wraps both keyboard and settings. Interaction changes in `KeyboardViewModel` + composables. No backend/model changes.

**Tech Stack:** Jetpack Compose, Material3, Material You dynamic colors.

---

## 1. Theme System

### WhisperBoardTheme composable
- New file: `ui/theme/WhisperBoardTheme.kt`
- Uses `dynamicDarkColorScheme`/`dynamicLightColorScheme` on Android 12+ (API 31+)
- Falls back to manual `darkColorScheme()`/`lightColorScheme()` on older devices
- Reads `isSystemInDarkTheme()` to pick scheme
- Applied in:
  - `SettingsActivity.setContent { WhisperBoardTheme { ... } }`
  - `KeyboardScreen` top-level wrapper (inside `ComposeKeyboardView`)

### Settings edge-to-edge
- `SettingsActivity.onCreate()`: call `enableEdgeToEdge()`
- Remove default `TopAppBar` or keep it but let the system status bar be transparent
- Scaffold handles inset padding automatically

### Drawable fixes
- `ic_star_filled.xml`: change `#FF000000` to `@android:color/white` — actual tint applied via Compose `tint` parameter

## 2. Keyboard Visual Overhaul

### Color mapping (Material3 tokens)
| Element | Color token |
|---|---|
| Keyboard background | `surfaceContainerLow` |
| Transcription area | `surfaceContainer` |
| Editing keys | `surfaceContainerHigh` (fill), `onSurface` (icon/text) |
| Language chip | `outline` border, `surfaceContainerHigh` fill, `onSurfaceVariant` text |
| Mic button idle | `primaryContainer` fill, `onPrimaryContainer` icon |
| Mic button recording | `error` fill, `onError` icon |
| Mic button processing | `surfaceVariant` fill + `CircularProgressIndicator` |

### Button styling
- Flat, no elevation
- Rounded corners: 12dp for editing keys, full-round (50%) for mic
- Ripple effect on tap (default Compose clickable behavior)
- "space" keeps text label; backspace, enter keep icons; comma, period show the character

### Mic button recording animation
- While recording: gentle pulsing scale animation (1.0 → 1.08 → 1.0, 800ms infinite)
- Push-to-talk active: scale to 1.15 with spring animation
- Use `animateFloatAsState` with `infiniteRepeatable`

## 3. Key Repeat (Backspace)

### Implementation
- `EditingKeys.kt`: Replace `clickable` on backspace with `pointerInput(Unit)` using `detectTapGestures`
- On tap: fire once
- On long press: enter repeat loop — `delay(400ms)` then `while(pressed) { fire; delay(50ms) }`
- Use `awaitPointerEvent` to detect release
- Alternative simpler approach: `Modifier.pointerInput` with `detectDragGesturesAfterLongPress` won't work cleanly. Better: custom `Modifier.repeatingClickable` that uses `InteractionSource` press state.

### Practical approach
```
Modifier.pointerInput(Unit) {
    detectTapGestures(
        onTap = { onAction() },
        onLongPress = {
            // start repeat coroutine
        }
    )
}
```
Problem: `onLongPress` fires once, doesn't track release. Better approach:

```
Modifier.pointerInput(Unit) {
    while (true) {
        val down = awaitPointerEventScope { awaitFirstDown() }
        onAction() // immediate first fire
        val repeatJob = coroutineScope.launch {
            delay(400)
            while (true) {
                onAction()
                delay(50)
            }
        }
        awaitPointerEventScope {
            waitForUpOrCancellation()
        }
        repeatJob.cancel()
    }
}
```

Only applied to backspace. Other keys keep simple `clickable`.

## 4. Mic Button Dual Mode

### Interaction model
- **Short tap** (<300ms press): Toggle recording on/off (existing behavior)
- **Long press** (≥300ms): Push-to-talk — recording starts at 300ms mark, stops on release

### State machine
```
IDLE --[pointer down]--> PENDING
PENDING --[release < 300ms]--> toggle recording (IDLE or RECORDING)
PENDING --[300ms elapsed]--> start recording, haptic buzz, scale up (PTT_ACTIVE)
PTT_ACTIVE --[release]--> stop recording & transcribe (IDLE)
RECORDING --[tap]--> stop recording & transcribe (IDLE)
```

### Visual feedback for PTT
- At 300ms hold: haptic `HapticFeedbackType.LongPress`
- Button scales to 1.15x with spring animation
- On release: spring back to 1.0x

### ViewModel changes
- Add `startRecording()` and `stopRecording()` as separate public methods (currently only `toggleRecording()`)
- `stopRecording()` handles the audio capture + transcription pipeline
- `toggleRecording()` delegates to start/stop based on `isRecording` state

## 5. Language Picker Overhaul

### Remove TextField search
- The TextField cannot receive input inside an IME Popup (we ARE the keyboard)
- Remove the `searchQuery` state and `TextField` composable

### Add alphabetical quick-scroll
- Right-side vertical strip of letters A-Z (thin, ~20dp wide)
- Each letter is a small touchable text
- Dragging or tapping a letter scrolls the `LazyColumn` to the first language starting with that letter
- Use `LazyListState.scrollToItem()` with a precomputed index map (letter → first item index)
- Letters with no matching languages are dimmed/disabled

### Layout
```
Row {
    LazyColumn(weight=1f) { ... languages ... }
    AlphabetSidebar(onLetterSelected = { scrollTo(it) })
}
```

### Keep favorites section at top
- Favorites pinned above "All Languages" as before
- Quick-scroll only affects the "All Languages" section

## 6. Haptic Feedback

- Add `LocalHapticFeedback.current` in `KeyboardScreen`
- Fire `HapticFeedbackType.TextHandleMove` (light) on:
  - Editing key tap
  - Mic button tap
  - Recording start/stop
- Fire `HapticFeedbackType.LongPress` on:
  - Push-to-talk activation (300ms hold)
  - Backspace repeat start

## Files Changed

| File | Change |
|---|---|
| **New:** `ui/theme/WhisperBoardTheme.kt` | Theme composable with dynamic colors |
| `settings/SettingsActivity.kt` | Edge-to-edge, wrap in theme |
| `settings/SettingsScreen.kt` | Remove TopAppBar or adjust for edge-to-edge |
| `ui/KeyboardView.kt` | Wrap in theme, update color tokens |
| `ui/MicButton.kt` | Dual mode interaction, pulsing animation, PTT scale |
| `ui/EditingKeys.kt` | Key repeat for backspace, flat styling, haptics |
| `ui/TranscriptionArea.kt` | Color token update |
| `ui/LanguageChip.kt` | Outline + distinct background |
| `ui/LanguagePickerDialog.kt` | Remove search, add A-Z sidebar |
| `ui/KeyboardViewModel.kt` | Split toggleRecording into start/stop |
| `ui/WaveformBar.kt` | Minor color token update |
| `res/drawable/ic_star_filled.xml` | Fix hardcoded black color |
