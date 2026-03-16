# Phase 3: Full Keyboard UI — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add waveform visualization, a complete language system (picker, favorites, IME subtypes), and fix Phase 2 deferred items (hot-reload, Snackbar errors, delete confirmation, download cancellation).

**Architecture:** Three vertical feature slices built sequentially: (1) language system end-to-end, (2) waveform visualizer, (3) deferred fixes. The language system introduces a shared DataStore (`AppPreferences`), `LanguageRepository`, `WhisperLanguages` data object, a searchable picker dialog, a settings section, and IME subtype management.

**Tech Stack:** Kotlin, Jetpack Compose Material3, DataStore Preferences, Android InputMethodService subtypes. No new dependencies — everything uses libraries already in the version catalog.

---

## Current State

- Keyboard: 280dp Column — TranscriptionArea, LanguageChip (stub), MicButton, EditingKeysRow
- `AudioPipeline` emits `waveformData: StateFlow<FloatArray>` every 100ms (not rendered)
- `KeyboardViewModel._activeLanguage` is a local `MutableStateFlow("auto")` — not persisted
- `ModelRepository` owns a private `preferencesDataStore("model_prefs")` at `ModelRepository.kt:22`
- `method.xml` has 2 subtypes: `en_US` and auto-detect
- Errors are written into `_transcribedText` (e.g., "[No model loaded]") instead of proper feedback
- Model loads once in `WhisperBoardIME.onCreate()` — no hot-reload

## Build & Run

```bash
# Build (from project root, inside nix devshell)
nix develop --command bash -c './gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2'

# Kotlin compile only (faster iteration)
nix develop --command bash -c './gradlew :app:compileDebugKotlin -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2'
```

---

## Slice 1: Language System

### Task 1: Extract shared DataStore to AppPreferences

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/model/AppPreferences.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt:1-22`

**Step 1: Create AppPreferences.kt**

```kotlin
package com.whisperboard.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")
```

**Step 2: Update ModelRepository to use shared DataStore**

In `ModelRepository.kt`, remove line 22:
```kotlin
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_prefs")
```

Remove the now-unused imports (lines 5-6, 10):
```kotlin
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
```

Replace all occurrences of `context.dataStore` with `context.appDataStore`. There are 5 occurrences:
- Line 55: `context.dataStore.data.map`
- Line 59: `context.dataStore.data.map`
- Line 64: `context.dataStore.edit`
- Line 76: `context.dataStore.data.first()`
- Line 136: `context.dataStore.edit`
- Line 159: `context.dataStore.edit`

Add import:
```kotlin
import com.whisperboard.model.appDataStore
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/AppPreferences.kt app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt
git commit -m "Extract shared DataStore to AppPreferences"
```

---

### Task 2: Create WhisperLanguages data object

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/model/WhisperLanguages.kt`

**Step 1: Create WhisperLanguages.kt**

All 100 language codes from whisper.cpp `g_lang` map (`third_party/whisper.cpp/src/whisper.cpp:280-381`). Display names are title-cased versions of the whisper.cpp names.

```kotlin
package com.whisperboard.model

object WhisperLanguages {
    /** Whisper language code → display name. Ordered by whisper.cpp language ID. */
    val codes: Map<String, String> = linkedMapOf(
        "en" to "English",
        "zh" to "Chinese",
        "de" to "German",
        "es" to "Spanish",
        "ru" to "Russian",
        "ko" to "Korean",
        "fr" to "French",
        "ja" to "Japanese",
        "pt" to "Portuguese",
        "tr" to "Turkish",
        "pl" to "Polish",
        "ca" to "Catalan",
        "nl" to "Dutch",
        "ar" to "Arabic",
        "sv" to "Swedish",
        "it" to "Italian",
        "id" to "Indonesian",
        "hi" to "Hindi",
        "fi" to "Finnish",
        "vi" to "Vietnamese",
        "he" to "Hebrew",
        "uk" to "Ukrainian",
        "el" to "Greek",
        "ms" to "Malay",
        "cs" to "Czech",
        "ro" to "Romanian",
        "da" to "Danish",
        "hu" to "Hungarian",
        "ta" to "Tamil",
        "no" to "Norwegian",
        "th" to "Thai",
        "ur" to "Urdu",
        "hr" to "Croatian",
        "bg" to "Bulgarian",
        "lt" to "Lithuanian",
        "la" to "Latin",
        "mi" to "Maori",
        "ml" to "Malayalam",
        "cy" to "Welsh",
        "sk" to "Slovak",
        "te" to "Telugu",
        "fa" to "Persian",
        "lv" to "Latvian",
        "bn" to "Bengali",
        "sr" to "Serbian",
        "az" to "Azerbaijani",
        "sl" to "Slovenian",
        "kn" to "Kannada",
        "et" to "Estonian",
        "mk" to "Macedonian",
        "br" to "Breton",
        "eu" to "Basque",
        "is" to "Icelandic",
        "hy" to "Armenian",
        "ne" to "Nepali",
        "mn" to "Mongolian",
        "bs" to "Bosnian",
        "kk" to "Kazakh",
        "sq" to "Albanian",
        "sw" to "Swahili",
        "gl" to "Galician",
        "mr" to "Marathi",
        "pa" to "Punjabi",
        "si" to "Sinhala",
        "km" to "Khmer",
        "sn" to "Shona",
        "yo" to "Yoruba",
        "so" to "Somali",
        "af" to "Afrikaans",
        "oc" to "Occitan",
        "ka" to "Georgian",
        "be" to "Belarusian",
        "tg" to "Tajik",
        "sd" to "Sindhi",
        "gu" to "Gujarati",
        "am" to "Amharic",
        "yi" to "Yiddish",
        "lo" to "Lao",
        "uz" to "Uzbek",
        "fo" to "Faroese",
        "ht" to "Haitian Creole",
        "ps" to "Pashto",
        "tk" to "Turkmen",
        "nn" to "Nynorsk",
        "mt" to "Maltese",
        "sa" to "Sanskrit",
        "lb" to "Luxembourgish",
        "my" to "Myanmar",
        "bo" to "Tibetan",
        "tl" to "Tagalog",
        "mg" to "Malagasy",
        "as" to "Assamese",
        "tt" to "Tatar",
        "haw" to "Hawaiian",
        "ln" to "Lingala",
        "ha" to "Hausa",
        "ba" to "Bashkir",
        "jw" to "Javanese",
        "su" to "Sundanese",
        "yue" to "Cantonese",
    )

    fun displayName(code: String): String = codes[code] ?: code.uppercase()
}
```

**Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/WhisperLanguages.kt
git commit -m "Add WhisperLanguages with all 100 Whisper language codes"
```

---

### Task 3: Create LanguageRepository

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/model/LanguageRepository.kt`

**Step 1: Create LanguageRepository.kt**

```kotlin
package com.whisperboard.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LanguageRepository(private val context: Context) {

    companion object {
        private val KEY_FAVORITES = stringSetPreferencesKey("favorite_languages")
        private val KEY_ACTIVE_LANGUAGE = stringPreferencesKey("active_language")
    }

    val favoriteLanguages: Flow<Set<String>> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FAVORITES] ?: emptySet()
    }

    val activeLanguage: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_LANGUAGE] ?: "auto"
    }

    suspend fun setActiveLanguage(code: String) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_LANGUAGE] = code
        }
    }

    suspend fun addFavorite(code: String) {
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current + code
        }
    }

    suspend fun removeFavorite(code: String) {
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_FAVORITES] ?: emptySet()
            prefs[KEY_FAVORITES] = current - code
        }
    }
}
```

**Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/LanguageRepository.kt
git commit -m "Add LanguageRepository for language favorites and active language"
```

---

### Task 4: Wire LanguageRepository into KeyboardViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt:23-41`
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt:60`

**Step 1: Update KeyboardViewModel constructor and language state**

In `KeyboardViewModel.kt`:

Change constructor to accept `LanguageRepository`:
```kotlin
class KeyboardViewModel(
    private val audioPipeline: AudioPipeline,
    private val languageRepository: LanguageRepository,
) : ViewModel() {
```

Add import:
```kotlin
import com.whisperboard.model.LanguageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
```

Replace the local `_activeLanguage` MutableStateFlow (lines 40-41):
```kotlin
private val _activeLanguage = MutableStateFlow("auto")
val activeLanguage: StateFlow<String> = _activeLanguage.asStateFlow()
```

With a DataStore-backed StateFlow:
```kotlin
val activeLanguage: StateFlow<String> = languageRepository.activeLanguage
    .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

val favoriteLanguages: StateFlow<Set<String>> = languageRepository.favoriteLanguages
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
```

Update `toggleRecording()` — replace `_activeLanguage.value` (line 74) with `activeLanguage.value`.

Add language mutation methods:
```kotlin
fun setLanguage(code: String) {
    viewModelScope.launch {
        languageRepository.setActiveLanguage(code)
    }
}

fun toggleFavorite(code: String) {
    viewModelScope.launch {
        val current = favoriteLanguages.value
        if (code in current) {
            languageRepository.removeFavorite(code)
        } else {
            languageRepository.addFavorite(code)
        }
    }
}
```

**Step 2: Update WhisperBoardIME to pass LanguageRepository**

In `WhisperBoardIME.kt`, change line 60:
```kotlin
viewModel = KeyboardViewModel(audioPipeline)
```
To:
```kotlin
val languageRepository = LanguageRepository(applicationContext)
viewModel = KeyboardViewModel(audioPipeline, languageRepository)
```

Add import:
```kotlin
import com.whisperboard.model.LanguageRepository
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt
git commit -m "Wire LanguageRepository into KeyboardViewModel for persisted language state"
```

---

### Task 5: Build LanguagePickerDialog

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/ui/LanguagePickerDialog.kt`

**Step 1: Create LanguagePickerDialog.kt**

```kotlin
package com.whisperboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import com.whisperboard.model.WhisperLanguages

@Composable
fun LanguagePickerDialog(
    activeLanguage: String,
    favorites: Set<String>,
    onSelectLanguage: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val allLanguages = WhisperLanguages.codes.entries.toList()

    val filtered = if (searchQuery.isBlank()) {
        allLanguages
    } else {
        val query = searchQuery.lowercase()
        allLanguages.filter { (code, name) ->
            code.contains(query) || name.lowercase().contains(query)
        }
    }

    val favoriteEntries = filtered.filter { it.key in favorites }
    val otherEntries = filtered.filter { it.key !in favorites }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
        text = {
            Column {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search languages...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(top = 8.dp)
                ) {
                    // Auto-detect option
                    item {
                        LanguageRow(
                            code = "auto",
                            displayName = "Auto-detect",
                            isActive = activeLanguage == "auto",
                            isFavorite = false,
                            showStar = false,
                            onSelect = { onSelectLanguage("auto") },
                            onToggleFavorite = {},
                        )
                    }

                    // Favorites section
                    if (favoriteEntries.isNotEmpty()) {
                        item {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(favoriteEntries, key = { "fav-${it.key}" }) { (code, name) ->
                            LanguageRow(
                                code = code,
                                displayName = name,
                                isActive = code == activeLanguage,
                                isFavorite = true,
                                showStar = true,
                                onSelect = { onSelectLanguage(code) },
                                onToggleFavorite = { onToggleFavorite(code) },
                            )
                        }
                    }

                    // All languages section
                    item {
                        Text(
                            text = "All Languages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(otherEntries, key = { "all-${it.key}" }) { (code, name) ->
                        LanguageRow(
                            code = code,
                            displayName = name,
                            isActive = code == activeLanguage,
                            isFavorite = false,
                            showStar = true,
                            onSelect = { onSelectLanguage(code) },
                            onToggleFavorite = { onToggleFavorite(code) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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

**Step 2: Add star icons**

Create two vector drawable files.

`app/src/main/res/drawable/ic_star_filled.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"/>
</vector>
```

`app/src/main/res/drawable/ic_star_outline.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24zM12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z"/>
</vector>
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/LanguagePickerDialog.kt app/src/main/res/drawable/ic_star_filled.xml app/src/main/res/drawable/ic_star_outline.xml
git commit -m "Add searchable language picker dialog with favorites"
```

---

### Task 6: Wire LanguageChip to LanguagePickerDialog

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/LanguageChip.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt:40-43`

**Step 1: Update LanguageChip to open picker**

Replace entire `LanguageChip.kt` content:

```kotlin
package com.whisperboard.ui

import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                style = MaterialTheme.typography.labelMedium
            )
        },
        modifier = modifier
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

**Step 2: Update KeyboardView.kt to pass new LanguageChip params**

In `KeyboardView.kt`, add state collection after line 24 (`val activeLanguage ...`):
```kotlin
val favoriteLanguages by viewModel.favoriteLanguages.collectAsState()
```

Replace the `LanguageChip` call (lines 40-43):
```kotlin
LanguageChip(
    language = activeLanguage,
    modifier = Modifier.padding(vertical = 4.dp)
)
```

With:
```kotlin
LanguageChip(
    language = activeLanguage,
    favorites = favoriteLanguages,
    onSelectLanguage = { viewModel.setLanguage(it) },
    onToggleFavorite = { viewModel.toggleFavorite(it) },
    modifier = Modifier.padding(vertical = 4.dp)
)
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/LanguageChip.kt app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt
git commit -m "Wire language chip to picker dialog with favorites"
```

---

### Task 7: Add language section to Settings

**Files:**
- Rename: `app/src/main/kotlin/com/whisperboard/settings/ModelManagerScreen.kt` → `SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`

**Step 1: Rename ModelManagerScreen.kt to SettingsScreen.kt**

```bash
git mv app/src/main/kotlin/com/whisperboard/settings/ModelManagerScreen.kt app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt
```

**Step 2: Update SettingsScreen.kt**

Rename the composable function from `ModelManagerScreen` to `SettingsScreen`. Add `LanguageRepository` parameter. Add a "Languages" section after the "Models" section.

Replace the entire file content:

```kotlin
package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.whisperboard.R
import com.whisperboard.model.DownloadProgress
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelInfo
import com.whisperboard.model.ModelManifest
import com.whisperboard.model.ModelRepository
import com.whisperboard.model.WhisperLanguages
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelRepository: ModelRepository,
    languageRepository: LanguageRepository,
) {
    val scope = rememberCoroutineScope()
    val downloadedModels by modelRepository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by modelRepository.activeModelName.collectAsState(initial = null)
    val downloadingModel by modelRepository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by modelRepository.downloadProgress.collectAsState(initial = null)
    val favoriteLanguages by languageRepository.favoriteLanguages.collectAsState(initial = emptySet())

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Whisper Board Settings") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Models section ---
            item {
                Text(
                    text = "Models",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(ModelManifest.models) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = model.name in downloadedModels,
                    isActive = model.name == activeModelName,
                    isDownloading = model.name == downloadingModel,
                    progress = if (model.name == downloadingModel) downloadProgress else null,
                    onDownload = {
                        scope.launch { modelRepository.download(model) }
                    },
                    onDelete = {
                        scope.launch { modelRepository.delete(model) }
                    },
                    onSelect = {
                        scope.launch { modelRepository.setActiveModel(model.name) }
                    },
                )
            }

            // --- Languages section ---
            item {
                Text(
                    text = "Languages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                Text(
                    text = "Star languages to pin them in the keyboard picker and Android IME switcher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(
                WhisperLanguages.codes.entries.toList(),
                key = { "lang-${it.key}" }
            ) { (code, name) ->
                LanguageSettingsRow(
                    code = code,
                    displayName = name,
                    isFavorite = code in favoriteLanguages,
                    onToggleFavorite = {
                        scope.launch {
                            if (code in favoriteLanguages) {
                                languageRepository.removeFavorite(code)
                            } else {
                                languageRepository.addFavorite(code)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LanguageSettingsRow(
    code: String,
    displayName: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = code.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isActive: Boolean,
    isDownloading: Boolean,
    progress: DownloadProgress?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (isActive) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                when {
                    isDownloading -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    isDownloaded && !isActive -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onSelect) { Text("Use") }
                            TextButton(onClick = onDelete) { Text("Delete") }
                        }
                    }
                    isDownloaded && isActive -> {
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    else -> {
                        TextButton(onClick = onDownload) { Text("Download") }
                    }
                }
            }

            if (isDownloading && progress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${progress.bytesDownloaded / 1_000_000} / ${progress.totalBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
```

**Step 3: Update SettingsActivity.kt**

Replace `setContent` block to pass both repositories:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    repository = ModelRepository(applicationContext)
    val languageRepository = LanguageRepository(applicationContext)

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    setContent {
        MaterialTheme {
            SettingsScreen(
                modelRepository = repository,
                languageRepository = languageRepository,
            )
        }
    }
}
```

Add import:
```kotlin
import com.whisperboard.model.LanguageRepository
```

**Step 4: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt
git commit -m "Add language favorites section to settings, rename to SettingsScreen"
```

---

### Task 8: Add IME subtypes for top 25 languages

**Files:**
- Modify: `app/src/main/res/xml/method.xml`
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt`

**Step 1: Update method.xml with pre-registered subtypes**

Replace entire `method.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.whisperboard.settings.SettingsActivity">
    <subtype
        android:label="Auto-detect"
        android:imeSubtypeLocale=""
        android:imeSubtypeMode="voice" />
    <subtype android:label="English" android:imeSubtypeLocale="en" android:imeSubtypeMode="voice" />
    <subtype android:label="Chinese" android:imeSubtypeLocale="zh" android:imeSubtypeMode="voice" />
    <subtype android:label="German" android:imeSubtypeLocale="de" android:imeSubtypeMode="voice" />
    <subtype android:label="Spanish" android:imeSubtypeLocale="es" android:imeSubtypeMode="voice" />
    <subtype android:label="Russian" android:imeSubtypeLocale="ru" android:imeSubtypeMode="voice" />
    <subtype android:label="Korean" android:imeSubtypeLocale="ko" android:imeSubtypeMode="voice" />
    <subtype android:label="French" android:imeSubtypeLocale="fr" android:imeSubtypeMode="voice" />
    <subtype android:label="Japanese" android:imeSubtypeLocale="ja" android:imeSubtypeMode="voice" />
    <subtype android:label="Portuguese" android:imeSubtypeLocale="pt" android:imeSubtypeMode="voice" />
    <subtype android:label="Turkish" android:imeSubtypeLocale="tr" android:imeSubtypeMode="voice" />
    <subtype android:label="Polish" android:imeSubtypeLocale="pl" android:imeSubtypeMode="voice" />
    <subtype android:label="Catalan" android:imeSubtypeLocale="ca" android:imeSubtypeMode="voice" />
    <subtype android:label="Dutch" android:imeSubtypeLocale="nl" android:imeSubtypeMode="voice" />
    <subtype android:label="Arabic" android:imeSubtypeLocale="ar" android:imeSubtypeMode="voice" />
    <subtype android:label="Swedish" android:imeSubtypeLocale="sv" android:imeSubtypeMode="voice" />
    <subtype android:label="Italian" android:imeSubtypeLocale="it" android:imeSubtypeMode="voice" />
    <subtype android:label="Indonesian" android:imeSubtypeLocale="id" android:imeSubtypeMode="voice" />
    <subtype android:label="Hindi" android:imeSubtypeLocale="hi" android:imeSubtypeMode="voice" />
    <subtype android:label="Finnish" android:imeSubtypeLocale="fi" android:imeSubtypeMode="voice" />
    <subtype android:label="Vietnamese" android:imeSubtypeLocale="vi" android:imeSubtypeMode="voice" />
    <subtype android:label="Hebrew" android:imeSubtypeLocale="he" android:imeSubtypeMode="voice" />
    <subtype android:label="Ukrainian" android:imeSubtypeLocale="uk" android:imeSubtypeMode="voice" />
    <subtype android:label="Greek" android:imeSubtypeLocale="el" android:imeSubtypeMode="voice" />
    <subtype android:label="Malay" android:imeSubtypeLocale="ms" android:imeSubtypeMode="voice" />
    <subtype android:label="Czech" android:imeSubtypeLocale="cs" android:imeSubtypeMode="voice" />
</input-method>
```

**Step 2: Handle subtype changes in WhisperBoardIME**

In `WhisperBoardIME.kt`, add the subtype change handler after `onFinishInputView`:

```kotlin
override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
    super.onCurrentInputMethodSubtypeChanged(newSubtype)
    val locale = newSubtype?.languageTag
        ?: newSubtype?.locale
        ?: ""
    val langCode = if (locale.isEmpty()) "auto" else locale.take(2).lowercase()
    Log.d(TAG, "IME subtype changed: $langCode")
    serviceScope.launch {
        languageRepository.setActiveLanguage(langCode)
    }
}
```

This requires storing `languageRepository` as a field. Move the `val languageRepository` from inside `onCreate()` to a `lateinit var` field:

```kotlin
private lateinit var languageRepository: LanguageRepository
```

And in `onCreate()`, change `val languageRepository = ...` to `languageRepository = ...`.

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/res/xml/method.xml app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt
git commit -m "Add IME subtypes for top 25 languages with subtype change handler"
```

---

## Slice 2: Waveform Visualizer

### Task 9: Create WaveformBar composable

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/ui/WaveformBar.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt`

**Step 1: Create WaveformBar.kt**

```kotlin
package com.whisperboard.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

private const val BAR_COUNT = 24

@Composable
fun WaveformBar(
    waveformData: FloatArray,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.primary

    // Downsample waveform data to BAR_COUNT amplitude values
    val amplitudes = remember(waveformData) {
        if (waveformData.isEmpty()) {
            FloatArray(BAR_COUNT) { 0f }
        } else {
            FloatArray(BAR_COUNT) { i ->
                val start = i * waveformData.size / BAR_COUNT
                val end = min((i + 1) * waveformData.size / BAR_COUNT, waveformData.size)
                if (start < end) {
                    var sum = 0f
                    for (j in start until end) {
                        sum += abs(waveformData[j])
                    }
                    (sum / (end - start)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            }
        }
    }

    WaveformCanvas(
        amplitudes = amplitudes,
        isRecording = isRecording,
        barColor = barColor,
        modifier = modifier
            .fillMaxWidth()
            .height(if (isRecording) 40.dp else 0.dp),
    )
}

@Composable
private fun WaveformCanvas(
    amplitudes: FloatArray,
    isRecording: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    // Animate each bar independently for smooth transitions
    val animatedAmplitudes = amplitudes.map { target ->
        animateFloatAsState(
            targetValue = if (isRecording) target else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "bar-amplitude",
        ).value
    }

    Canvas(modifier = modifier) {
        if (size.height <= 0f || size.width <= 0f) return@Canvas

        val barWidth = size.width / (BAR_COUNT * 1.5f)
        val gap = barWidth * 0.5f
        val totalBarWidth = barWidth + gap
        val startX = (size.width - totalBarWidth * BAR_COUNT + gap) / 2f
        val minBarHeight = 4f
        val maxBarHeight = size.height * 0.9f
        val cornerRadius = barWidth / 2f

        animatedAmplitudes.forEachIndexed { i, amplitude ->
            val barHeight = minBarHeight + amplitude * (maxBarHeight - minBarHeight)
            val x = startX + i * totalBarWidth
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
        }
    }
}
```

**Step 2: Add WaveformBar to KeyboardView.kt**

In `KeyboardView.kt`, add state collection for waveform data after other state collections:
```kotlin
val waveformData by viewModel.waveformData.collectAsState()
```

Add `WaveformBar` between `MicButton` and `EditingKeysRow`:

```kotlin
WaveformBar(
    waveformData = waveformData,
    isRecording = isRecording,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp),
)
```

Change the overall height from `280.dp` to `320.dp` to accommodate the waveform.

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/WaveformBar.kt app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt
git commit -m "Add animated bar waveform visualizer to keyboard"
```

---

## Slice 3: Deferred Fixes

### Task 10: Hot-reload model in IME on selection change

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt:62-78`

**Step 1: Replace one-shot model load with reactive flow**

In `WhisperBoardIME.kt`, replace the current model loading block in `onCreate()` (the `serviceScope.launch` that reads `repository.getActiveModelPath()`) with a `collectLatest` on `activeModelName`:

```kotlin
private lateinit var modelRepository: ModelRepository
```

In `onCreate()`, replace:
```kotlin
val repository = ModelRepository(applicationContext)

serviceScope.launch {
    try {
        val modelPath = repository.getActiveModelPath()
        if (modelPath != null) {
            Log.d(TAG, "Loading Whisper model from $modelPath")
            val ctx = WhisperContext.createContext(modelPath)
            viewModel.setWhisperContext(ctx)
            Log.d(TAG, "Whisper model loaded")
        } else {
            Log.w(TAG, "No active model selected — open Settings to download one")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load Whisper model", e)
    }
}
```

With:
```kotlin
modelRepository = ModelRepository(applicationContext)

serviceScope.launch {
    modelRepository.activeModelName.collectLatest { modelName ->
        try {
            // Close previous context
            viewModel.setWhisperContext(null)

            if (modelName == null) {
                Log.w(TAG, "No active model selected — open Settings to download one")
                return@collectLatest
            }

            val modelPath = modelRepository.getActiveModelPath()
            if (modelPath == null) {
                Log.w(TAG, "Active model $modelName not found on disk")
                return@collectLatest
            }

            Log.d(TAG, "Loading Whisper model from $modelPath")
            val ctx = WhisperContext.createContext(modelPath)
            viewModel.setWhisperContext(ctx)
            Log.d(TAG, "Whisper model loaded: $modelName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Whisper model", e)
        }
    }
}
```

Add import:
```kotlin
import kotlinx.coroutines.flow.collectLatest
```

Update `cleanup()` in `KeyboardViewModel.kt` to handle null context gracefully — `setWhisperContext(null)` should close the old context before setting null:

In `KeyboardViewModel.kt`, change `setWhisperContext`:
```kotlin
fun setWhisperContext(context: WhisperContext?) {
    whisperContext?.close()
    whisperContext = context
}
```

And remove the `close()` call from `cleanup()` since `setWhisperContext(null)` now handles it:
```kotlin
fun cleanup() {
    setWhisperContext(null)
}
```

**Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt
git commit -m "Hot-reload Whisper model when active model changes in DataStore"
```

---

### Task 11: Replace error text with Snackbar feedback

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt`

**Step 1: Add error SharedFlow to KeyboardViewModel**

In `KeyboardViewModel.kt`, add:
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

Add field after `waveformData`:
```kotlin
private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()
```

In `toggleRecording()`, replace all `_transcribedText.value = "[...]"` error messages with `_errorMessage.tryEmit(...)`:

- Line 68-69 (no model while stopping): replace `_transcribedText.value = "[No model loaded]"` with `_errorMessage.tryEmit("No model loaded")`
- Line 89-90 (no model while starting): replace `_transcribedText.value = "[No model loaded]"` with `_errorMessage.tryEmit("No model loaded")`
- Line 94-95 (no permission): replace `_transcribedText.value = "[Microphone permission required]"` with `_errorMessage.tryEmit("Microphone permission required")`
- Line 77 (transcription error catch): replace `_transcribedText.value = "[Transcription error]"` with `_errorMessage.tryEmit("Transcription failed")`

**Step 2: Add SnackbarHost to KeyboardView**

In `KeyboardView.kt`, add imports:
```kotlin
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
```

Wrap the content in a `Box` with a `SnackbarHost`:

```kotlin
MaterialTheme {
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ... existing content (TranscriptionArea, LanguageChip, etc.)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

Add import:
```kotlin
import androidx.compose.foundation.layout.Box
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt app/src/main/kotlin/com/whisperboard/ui/KeyboardView.kt
git commit -m "Replace error text in transcription area with Snackbar feedback"
```

---

### Task 12: Add delete confirmation for active model

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt`

**Step 1: Add confirmation dialog state and logic**

In `SettingsScreen.kt`, add a `modelToDelete` state inside `SettingsScreen`:

```kotlin
var modelToDelete by remember { mutableStateOf<ModelInfo?>(null) }
```

Update the `onDelete` callback in the `ModelCard` to check if the model is active:

```kotlin
onDelete = {
    if (model.name == activeModelName) {
        modelToDelete = model
    } else {
        scope.launch { modelRepository.delete(model) }
    }
},
```

Add the confirmation dialog after the `Scaffold`:

```kotlin
modelToDelete?.let { model ->
    AlertDialog(
        onDismissRequest = { modelToDelete = null },
        title = { Text("Delete Active Model?") },
        text = { Text("\"${model.displayName}\" is currently in use. The keyboard will stop working until you select another model.") },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { modelRepository.delete(model) }
                modelToDelete = null
            }) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = { modelToDelete = null }) {
                Text("Cancel")
            }
        },
    )
}
```

**Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt
git commit -m "Add delete confirmation dialog for active model"
```

---

### Task 13: Add download cancellation

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt`

**Step 1: Add cancellation support to ModelRepository**

In `ModelRepository.kt`, add a field to track the active OkHttp call:

```kotlin
@Volatile
private var activeCall: okhttp3.Call? = null
```

In the `download()` method, store the call before executing:

Change line 94-95 from:
```kotlin
val request = Request.Builder().url(model.url).build()
val response = client.newCall(request).execute()
```

To:
```kotlin
val request = Request.Builder().url(model.url).build()
val call = client.newCall(request)
activeCall = call
val response = call.execute()
```

In the `finally` block (after line 148), add:
```kotlin
activeCall = null
```

Add the cancel method:
```kotlin
fun cancelDownload() {
    activeCall?.cancel()
    activeCall = null
}
```

**Step 2: Add cancel button to SettingsScreen**

In `SettingsScreen.kt`, update the `ModelCard` composable to accept an `onCancel` callback:

Add parameter:
```kotlin
onCancel: () -> Unit,
```

In the `when` block inside `ModelCard`, change the `isDownloading` branch from:
```kotlin
isDownloading -> {
    CircularProgressIndicator(modifier = Modifier.size(24.dp))
}
```

To:
```kotlin
isDownloading -> {
    TextButton(onClick = onCancel) { Text("Cancel") }
}
```

In the `SettingsScreen` composable, pass the new callback:
```kotlin
onCancel = {
    modelRepository.cancelDownload()
},
```

**Step 3: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt
git commit -m "Add download cancellation support"
```

---

### Task 14: Full build and push

**Step 1: Full debug build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 2: Push to main**

```bash
git push origin main
```

**Step 3: Verify CI passes**

```bash
gh run watch --exit-status
```

Expected: CI green.

---

## Verification Checklist

### Language system
1. Tap language chip → picker opens with search, favorites, all languages
2. Search for "French" → filters correctly
3. Star French → appears in favorites section
4. Select French → chip shows "French", transcribe in French → correct output
5. Open Settings → Languages section shows favorites with filled stars
6. Star/unstar in Settings → reflected in keyboard picker
7. Switch via Android IME switcher → chip and transcription update

### Waveform
8. Tap mic → bar equalizer appears, animates with voice
9. Stop recording → bars collapse smoothly
10. While idle → no waveform visible (0dp height)

### Deferred fixes
11. Switch active model in Settings → IME hot-reloads without restart
12. Tap mic with no model → Snackbar error (not in transcription area)
13. Delete active model → confirmation dialog appears
14. Confirm delete → model removed, IME falls back to null context
15. Start download → tap Cancel → download stops, no partial file
