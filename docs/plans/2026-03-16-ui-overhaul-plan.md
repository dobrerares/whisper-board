# UI Overhaul Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix dark theme, improve keyboard aesthetics, add push-to-talk, fix language picker, add key repeat.

**Architecture:** New `WhisperBoardTheme` wraps both keyboard and settings with Material You dynamic colors. Mic button uses pointer input state machine for dual tap/hold modes. Backspace uses coroutine-based repeat loop. Language picker replaces broken TextField with A-Z quick-scroll.

**Tech Stack:** Jetpack Compose, Material3, Material You dynamic colors, coroutines for key repeat.

---

### Task 1: Theme System

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/ui/theme/WhisperBoardTheme.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt`
- Modify: `app/src/main/res/drawable/ic_star_filled.xml`

**Step 1: Create WhisperBoardTheme.kt**

```kotlin
package com.whisperboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun WhisperBoardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
```

**Step 2: Update SettingsActivity.kt**

Replace `MaterialTheme {` with `WhisperBoardTheme {` and add edge-to-edge:

```kotlin
// Add imports:
import androidx.activity.enableEdgeToEdge
import com.whisperboard.ui.theme.WhisperBoardTheme

// In onCreate(), before setContent:
enableEdgeToEdge()

// Replace setContent block:
setContent {
    WhisperBoardTheme {
        SettingsScreen(
            modelRepository = repository,
            languageRepository = languageRepository,
            apiSettingsRepository = apiSettingsRepository,
            imeEnabled = imeEnabled.value,
            imeSelected = imeSelected.value,
            onOpenImeSettings = {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            onOpenImePicker = {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
        )
    }
}
```

**Step 3: Update KeyboardView.kt**

Replace `MaterialTheme {` with `WhisperBoardTheme {`. Also set `surfaceContainerLow` as keyboard background:

```kotlin
// Add import:
import androidx.compose.foundation.background
import com.whisperboard.ui.theme.WhisperBoardTheme

// Replace MaterialTheme { with:
WhisperBoardTheme {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // ... rest unchanged
    }
}
```

**Step 4: Fix ic_star_filled.xml**

Change `android:fillColor="#FF000000"` to `android:fillColor="@android:color/white"`.

**Step 5: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/theme/WhisperBoardTheme.kt \
       app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt \
       app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt \
       app/src/main/res/drawable/ic_star_filled.xml
git commit -m "feat: add WhisperBoardTheme with Material You dynamic colors"
```

---

### Task 2: Keyboard Visual Polish

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/TranscriptionArea.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/LanguageChip.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/MicButton.kt`

**Step 1: Update TranscriptionArea.kt**

Change `surfaceVariant` to `surfaceContainer` and text color to `onSurface`:

```kotlin
package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TranscriptionArea(
    text: String,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(80.dp)
            .clickable(enabled = text.isNotEmpty()) { onCommit() },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Tap mic to start...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
```

**Step 2: Update LanguageChip.kt with outline styling**

```kotlin
package com.whisperboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.WhisperLanguages

@Composable
fun LanguageChip(
    language: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    val displayText = if (language == "auto") {
        "Auto-detect"
    } else {
        WhisperLanguages.displayName(language)
    }

    AssistChip(
        onClick = { showPicker = true },
        label = {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier.height(28.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = AssistChipDefaults.assistChipBorder(
            borderColor = MaterialTheme.colorScheme.outline,
        ),
    )

    if (showPicker) {
        LanguagePickerDialog(
            activeLanguage = language,
            favorites = favorites,
            onSelectLanguage = { code ->
                onSelectLanguage(code)
                showPicker = false
            },
            onToggleFavorite = onToggleFavorite,
            onDismiss = { showPicker = false },
        )
    }
}
```

**Step 3: Update EditingKeys.kt with flat styling**

Change EditKey color from `surfaceVariant` to `surfaceContainerHigh` and corner radius to 12dp:

In the `EditKey` composable, change:
- `color = MaterialTheme.colorScheme.surfaceVariant` → `color = MaterialTheme.colorScheme.surfaceContainerHigh`
- `shape = MaterialTheme.shapes.small` → `shape = MaterialTheme.shapes.medium` (medium = 12dp in Material3)

**Step 4: Update MicButton.kt — remove elevation, add pulse animation**

```kotlin
package com.whisperboard.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R

@Composable
fun MicButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isProcessing -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val contentColor = when {
        isRecording -> MaterialTheme.colorScheme.onError
        isProcessing -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    val scale = if (isRecording) pulseScale else 1f

    FloatingActionButton(
        onClick = { if (!isProcessing) onToggle() },
        modifier = modifier
            .size(80.dp)
            .scale(scale),
        shape = CircleShape,
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
        )
    ) {
        when {
            isProcessing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = contentColor,
                    strokeWidth = 3.dp
                )
            }
            else -> {
                Icon(
                    painter = painterResource(
                        id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                    ),
                    contentDescription = if (isRecording) "Stop recording" else "Start recording",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}
```

**Step 5: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/TranscriptionArea.kt \
       app/src/main/kotlin/com/whisperboard/ui/LanguageChip.kt \
       app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt \
       app/src/main/kotlin/com/whisperboard/ui/MicButton.kt
git commit -m "feat: flat Gboard-style keyboard buttons with proper color tokens"
```

---

### Task 3: Backspace Key Repeat

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt`

**Step 1: Rewrite EditingKeys.kt with key repeat on backspace**

Replace the full file:

```kotlin
package com.whisperboard.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperboard.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditingKeysRow(
    onAction: (EditAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RepeatableEditKey(
            onAction = { onAction(EditAction.Backspace) },
            repeatDelayMs = 400L,
            repeatIntervalMs = 50L,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_backspace),
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        EditKey(onClick = { onAction(EditAction.Comma) }) {
            Text(",", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Space) }, weight = 3f) {
            Text("space", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Period) }) {
            Text(".", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }

        EditKey(onClick = { onAction(EditAction.Enter) }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_enter),
                contentDescription = "Enter",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RowScope.RepeatableEditKey(
    onAction: () -> Unit,
    repeatDelayMs: Long,
    repeatIntervalMs: Long,
    weight: Float = 1f,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .padding(horizontal = 2.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onAction()
                    coroutineScope {
                        val repeatJob = launch {
                            delay(repeatDelayMs)
                            while (true) {
                                onAction()
                                delay(repeatIntervalMs)
                            }
                        }
                        waitForUpOrCancellation()
                        repeatJob.cancel()
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun RowScope.EditKey(
    onClick: () -> Unit,
    weight: Float = 1f,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(weight)
            .height(44.dp)
            .padding(horizontal = 2.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
```

**Step 2: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt
git commit -m "feat: add key repeat on backspace long-press"
```

---

### Task 4: Mic Button Dual Mode (Tap Toggle + Push-to-Talk)

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/MicButton.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt`

**Step 1: Split toggleRecording into start/stop in KeyboardViewModel.kt**

Add these two public methods alongside the existing `toggleRecording()`:

```kotlin
fun startRecording() {
    if (_isRecording.value) return
    val router = engineRouter
    if (router == null || !router.canTranscribe()) {
        _errorMessage.tryEmit("No transcription engine available")
        return
    }
    if (!audioPipeline.hasRecordPermission()) {
        _errorMessage.tryEmit("Microphone permission required")
        return
    }
    audioPipeline.startRecording(viewModelScope)
    _isRecording.value = true
}

fun stopRecording() {
    if (!_isRecording.value) return
    _isRecording.value = false
    viewModelScope.launch {
        try {
            val samples = audioPipeline.stopRecording()
            if (samples.isEmpty()) return@launch
            val router = engineRouter ?: return@launch
            _isProcessing.value = true
            val start = System.currentTimeMillis()
            val text = router.transcribe(samples, activeLanguage.value)
            val elapsed = System.currentTimeMillis() - start
            Log.d(TAG, "Transcription done in ${elapsed}ms: \"$text\"")
            _transcribedText.value = text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            _errorMessage.tryEmit(e.message ?: "Transcription failed")
        } finally {
            _isProcessing.value = false
        }
    }
}
```

Then simplify `toggleRecording()` to delegate:

```kotlin
fun toggleRecording() {
    if (_isRecording.value) {
        stopRecording()
    } else {
        startRecording()
    }
}
```

**Step 2: Rewrite MicButton.kt with dual-mode pointer input**

```kotlin
package com.whisperboard.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PTT_THRESHOLD_MS = 300L

@Composable
fun MicButton(
    isRecording: Boolean,
    isProcessing: Boolean,
    onToggle: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var isPtt by remember { mutableStateOf(false) }

    val containerColor = when {
        isRecording -> MaterialTheme.colorScheme.error
        isProcessing -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    val contentColor = when {
        isRecording -> MaterialTheme.colorScheme.onError
        isProcessing -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    // Pulse while recording in toggle mode
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )

    // PTT scale-up
    val pttScale by animateFloatAsState(
        targetValue = if (isPtt) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "ptt-scale",
    )

    val scale = when {
        isPtt -> pttScale
        isRecording -> pulseScale
        else -> 1f
    }

    Box(
        modifier = modifier
            .size(80.dp)
            .scale(scale)
            .pointerInput(isRecording, isProcessing) {
                if (isProcessing) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    if (isRecording) {
                        // Already recording in toggle mode — tap to stop
                        waitForUpOrCancellation()
                        onStopRecording()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else {
                        // Not recording — detect short tap vs long press
                        var pttActivated = false
                        coroutineScope {
                            val timerJob = launch {
                                delay(PTT_THRESHOLD_MS)
                                // Long press → push-to-talk
                                pttActivated = true
                                isPtt = true
                                onStartRecording()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            waitForUpOrCancellation()
                            timerJob.cancel()
                        }
                        if (pttActivated) {
                            // Release after PTT
                            isPtt = false
                            onStopRecording()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else {
                            // Short tap → toggle
                            onToggle()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        FloatingActionButton(
            onClick = {},  // handled by pointerInput
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
            ),
        ) {
            when {
                isProcessing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = contentColor,
                        strokeWidth = 3.dp,
                    )
                }
                else -> {
                    Icon(
                        painter = painterResource(
                            id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                        ),
                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}
```

**Step 3: Update KeyboardView.kt to pass new callbacks**

Change the MicButton call:

```kotlin
MicButton(
    isRecording = isRecording,
    isProcessing = isProcessing,
    onToggle = { viewModel.toggleRecording() },
    onStartRecording = { viewModel.startRecording() },
    onStopRecording = { viewModel.stopRecording() },
    modifier = Modifier.padding(vertical = 8.dp)
)
```

**Step 4: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt \
       app/src/main/kotlin/com/whisperboard/ui/MicButton.kt \
       app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt
git commit -m "feat: add push-to-talk (long-press) and toggle (tap) mic modes"
```

---

### Task 5: Language Picker — Remove Search, Add A-Z Sidebar

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/LanguagePickerDialog.kt`

**Step 1: Rewrite LanguagePickerDialog.kt**

Replace the full file:

```kotlin
package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.whisperboard.R
import com.whisperboard.model.WhisperLanguages
import kotlinx.coroutines.launch

@Composable
fun LanguagePickerDialog(
    activeLanguage: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val allLanguages = WhisperLanguages.codes.entries.toList()
    val favoriteEntries = allLanguages.filter { it.key in favorites }
    val otherEntries = allLanguages.filter { it.key !in favorites }

    // Build item list to compute indices for A-Z sidebar
    data class ListItem(val key: String, val type: String, val code: String = "", val name: String = "")

    val items = buildList {
        // Auto-detect
        add(ListItem(key = "auto", type = "language", code = "auto", name = "Auto-detect"))
        // Favorites
        if (favoriteEntries.isNotEmpty()) {
            add(ListItem(key = "header-fav", type = "header"))
            favoriteEntries.forEach { (code, name) ->
                add(ListItem(key = "fav-$code", type = "language", code = code, name = name))
            }
        }
        // All languages header
        add(ListItem(key = "header-all", type = "header"))
        otherEntries.forEach { (code, name) ->
            add(ListItem(key = "all-$code", type = "language", code = code, name = name))
        }
    }

    // Map first letter → index in items list (only "All Languages" section)
    val letterToIndex = remember(otherEntries) {
        val map = mutableMapOf<Char, Int>()
        val allHeaderIndex = items.indexOfFirst { it.key == "header-all" }
        otherEntries.forEachIndexed { i, (_, name) ->
            val letter = name.first().uppercaseChar()
            if (letter !in map) {
                map[letter] = allHeaderIndex + 1 + i
            }
        }
        map
    }
    val availableLetters = remember(letterToIndex) {
        ('A'..'Z').filter { it in letterToIndex }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Language",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Row(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp),
                        state = listState,
                    ) {
                        items(items, key = { it.key }) { item ->
                            when (item.type) {
                                "header" -> {
                                    val label = if (item.key == "header-fav") "Favorites" else "All Languages"
                                    val color = if (item.key == "header-fav") {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                "language" -> {
                                    LanguageRow(
                                        code = item.code,
                                        displayName = item.name,
                                        isActive = item.code == activeLanguage,
                                        isFavorite = item.code in favorites,
                                        showStar = item.code != "auto",
                                        onSelect = { onSelectLanguage(item.code) },
                                        onToggleFavorite = { onToggleFavorite(item.code) },
                                    )
                                }
                            }
                        }
                    }

                    // A-Z quick scroll sidebar
                    AlphabetSidebar(
                        letters = availableLetters,
                        onLetterSelected = { letter ->
                            val index = letterToIndex[letter] ?: return@AlphabetSidebar
                            scope.launch { listState.scrollToItem(index) }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(24.dp)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlphabetSidebar(
    letters: List<Char>,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    var heightPx = remember { 0f }
    val density = LocalDensity.current

    Column(
        modifier = modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val index = ((down.position.y / heightPx) * letters.size)
                        .toInt()
                        .coerceIn(0, letters.lastIndex)
                    onLetterSelected(letters[index])

                    drag(down.id) { change ->
                        change.consume()
                        val dragIndex = ((change.position.y / heightPx) * letters.size)
                            .toInt()
                            .coerceIn(0, letters.lastIndex)
                        onLetterSelected(letters[dragIndex])
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            Text(
                text = letter.toString(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LanguageRow(
    code: String,
    displayName: String,
    isActive: Boolean,
    isFavorite: Boolean,
    showStar: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (code != "auto") {
                Text(
                    text = code.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showStar) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    painter = painterResource(
                        id = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
                    ),
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
```

**Step 2: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/LanguagePickerDialog.kt
git commit -m "feat: replace broken search with A-Z quick-scroll in language picker"
```

---

### Task 6: Haptic Feedback on Editing Keys

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt`

**Step 1: Add haptic feedback to EditKey and RepeatableEditKey**

In `EditKey`, wrap `onClick` to include haptics:

```kotlin
@Composable
private fun RowScope.EditKey(
    onClick: () -> Unit,
    weight: Float = 1f,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        // ... rest unchanged
    )
}
```

In `RepeatableEditKey`, add haptic on first press inside the `awaitEachGesture`:

After `onAction()` (the first fire), add:
```kotlin
haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
```

Add imports:
```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
```

**Step 2: Build and verify**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/EditingKeys.kt
git commit -m "feat: add haptic feedback to editing keys"
```
