# Custom Model Import — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow users to import custom GGML whisper.cpp models via file picker or URL, displayed alongside built-in models.

**Architecture:** Extend `ModelInfo` with `isCustom`/`languageHint` fields. Persist custom models as JSON in DataStore. `ModelRepository` exposes a combined `allModels` flow. New `ImportModelDialog` composable handles both import paths. File picker uses Android's `OpenDocument` contract; URL download reuses existing `download()` logic. Validation loads the model with `WhisperContext.createContext()` and immediately closes it.

**Tech Stack:** Kotlin, Compose Material3, DataStore Preferences, `org.json` (Android SDK — no new dependencies), OkHttp (existing)

---

### Task 1: Extend ModelInfo with custom model fields

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/model/ModelInfo.kt`

**Step 1: Add isCustom and languageHint fields**

```kotlin
package com.whisperboard.model

import org.json.JSONArray
import org.json.JSONObject

data class ModelInfo(
    val name: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val isCustom: Boolean = false,
    val languageHint: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("displayName", displayName)
        put("fileName", fileName)
        put("url", url)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("languageHint", languageHint ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelInfo = ModelInfo(
            name = json.getString("name"),
            displayName = json.getString("displayName"),
            fileName = json.getString("fileName"),
            url = json.optString("url", ""),
            sizeBytes = json.optLong("sizeBytes", 0),
            sha256 = json.optString("sha256", ""),
            isCustom = true,
            languageHint = json.optString("languageHint").takeIf { it.isNotEmpty() && it != "null" },
        )

        fun listToJson(models: List<ModelInfo>): String =
            JSONArray(models.map { it.toJson() }).toString()

        fun listFromJson(json: String): List<ModelInfo> {
            if (json.isBlank()) return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/ModelInfo.kt
git commit -m "feat: add isCustom/languageHint fields + JSON serialization to ModelInfo"
```

---

### Task 2: Add custom model persistence to ModelRepository

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt`

**Step 1: Add custom model storage and combined allModels flow**

Add to companion object:
```kotlin
private val KEY_CUSTOM_MODELS = stringPreferencesKey("custom_models")
```

Add after the `activeModelName` flow:
```kotlin
val customModels: Flow<List<ModelInfo>> = context.appDataStore.data.map { prefs ->
    val json = prefs[KEY_CUSTOM_MODELS] ?: ""
    ModelInfo.listFromJson(json)
}

val allModels: Flow<List<ModelInfo>> = customModels.map { custom ->
    ModelManifest.models + custom
}
```

Add custom model CRUD methods:
```kotlin
suspend fun addCustomModel(model: ModelInfo) {
    context.appDataStore.edit { prefs ->
        val current = ModelInfo.listFromJson(prefs[KEY_CUSTOM_MODELS] ?: "")
        prefs[KEY_CUSTOM_MODELS] = ModelInfo.listToJson(current + model)
        prefs[KEY_DOWNLOADED] = (prefs[KEY_DOWNLOADED] ?: emptySet()) + model.name
    }
}

private suspend fun removeCustomModel(name: String) {
    context.appDataStore.edit { prefs ->
        val current = ModelInfo.listFromJson(prefs[KEY_CUSTOM_MODELS] ?: "")
        prefs[KEY_CUSTOM_MODELS] = ModelInfo.listToJson(current.filter { it.name != name })
    }
}
```

**Step 2: Fix getActiveModelPath() to find custom models**

Replace the current `getActiveModelPath()`:
```kotlin
suspend fun getActiveModelPath(): String? {
    val prefs = context.appDataStore.data.first()
    val name = prefs[KEY_ACTIVE] ?: return null
    val model = findModel(name, prefs) ?: return null
    val file = getModelFile(model)
    return if (file.exists()) file.absolutePath else null
}

private fun findModel(name: String, prefs: Preferences? = null): ModelInfo? {
    ModelManifest.getByName(name)?.let { return it }
    val json = prefs?.get(KEY_CUSTOM_MODELS) ?: ""
    return ModelInfo.listFromJson(json).find { it.name == name }
}
```

Note: `findModel` needs `import androidx.datastore.preferences.core.Preferences` (already imported via the edit import).

**Step 3: Update delete() to also remove custom model metadata**

After the existing `delete()` logic, add the custom model removal:
```kotlin
suspend fun delete(model: ModelInfo) {
    withContext(Dispatchers.IO) {
        getModelFile(model).delete()
    }
    context.appDataStore.edit { prefs ->
        val current = prefs[KEY_DOWNLOADED] ?: emptySet()
        prefs[KEY_DOWNLOADED] = current - model.name

        if (prefs[KEY_ACTIVE] == model.name) {
            prefs.remove(KEY_ACTIVE)
        }
    }
    if (model.isCustom) {
        removeCustomModel(model.name)
    }
    Log.d(TAG, "Deleted ${model.name}")
}
```

**Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt
git commit -m "feat: add custom model persistence and allModels flow to ModelRepository"
```

---

### Task 3: Add import methods to ModelRepository

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt`

**Step 1: Add importFromFile method**

This copies a file from a content URI into the models directory:
```kotlin
suspend fun importFromFile(
    uri: android.net.Uri,
    displayName: String,
    languageHint: String?,
): Result<ModelInfo> = withContext(Dispatchers.IO) {
    val timestamp = System.currentTimeMillis()
    val fileName = "custom_${timestamp}.bin"
    val destFile = File(modelsDir, fileName)

    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        } ?: return@withContext Result.failure(Exception("Cannot open file"))

        val model = ModelInfo(
            name = "custom_$timestamp",
            displayName = displayName,
            fileName = fileName,
            url = "",
            sizeBytes = destFile.length(),
            sha256 = "",
            isCustom = true,
            languageHint = languageHint,
        )

        addCustomModel(model)
        Result.success(model)
    } catch (e: Exception) {
        destFile.delete()
        Result.failure(e)
    }
}
```

**Step 2: Add importFromUrl method**

This reuses the existing download infrastructure:
```kotlin
suspend fun importFromUrl(
    url: String,
    displayName: String,
    languageHint: String?,
): Result<ModelInfo> {
    val timestamp = System.currentTimeMillis()
    val fileName = "custom_${timestamp}.bin"

    val model = ModelInfo(
        name = "custom_$timestamp",
        displayName = displayName,
        fileName = fileName,
        url = url,
        sizeBytes = 0L,
        sha256 = "",
        isCustom = true,
        languageHint = languageHint,
    )

    val result = download(model)
    if (result.isSuccess) {
        addCustomModel(model.copy(sizeBytes = result.getOrThrow().length()))
    }
    return result.map { model }
}
```

**Step 3: Add validateModel method**

```kotlin
suspend fun validateModel(model: ModelInfo): Boolean = withContext(Dispatchers.IO) {
    val path = getModelFile(model).absolutePath
    try {
        val ctx = com.whisperboard.whisper.WhisperContext.createContext(path)
        ctx.close()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Model validation failed for ${model.name}", e)
        false
    }
}
```

**Step 4: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt
git commit -m "feat: add importFromFile, importFromUrl, and validateModel to ModelRepository"
```

---

### Task 4: Create ImportModelDialog composable

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/settings/ImportModelDialog.kt`

**Step 1: Create the dialog**

```kotlin
package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.WhisperLanguages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportModelDialog(
    onDismiss: () -> Unit,
    onImportFile: (displayName: String, languageHint: String?) -> Unit,
    onImportUrl: (url: String, displayName: String, languageHint: String?) -> Unit,
    selectedFileName: String?,
    onBrowseFile: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var languageExpanded by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }

    val languageLabel = selectedLanguage?.let { WhisperLanguages.displayName(it) } ?: "Auto-detect"

    val canImport = displayName.isNotBlank() && when (tab) {
        0 -> selectedFileName != null
        1 -> url.isNotBlank()
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Model") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) {
                        Text("File", modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("URL", modifier = Modifier.padding(12.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (tab) {
                    0 -> {
                        OutlinedButton(
                            onClick = onBrowseFile,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(selectedFileName ?: "Browse...")
                        }
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            placeholder = { Text("https://huggingface.co/.../model.bin") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = languageLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Language (optional)") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                        },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto-detect") },
                            onClick = {
                                selectedLanguage = null
                                languageExpanded = false
                            },
                        )
                        WhisperLanguages.codes.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedLanguage = code
                                    languageExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (tab) {
                        0 -> onImportFile(displayName, selectedLanguage)
                        1 -> onImportUrl(url, displayName, selectedLanguage)
                    }
                },
                enabled = canImport,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
```

**Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/ImportModelDialog.kt
git commit -m "feat: create ImportModelDialog composable with file/URL tabs"
```

---

### Task 5: Update SettingsScreen to use allModels and show import UI

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt`

**Step 1: Switch model list from ModelManifest to allModels flow**

In `SettingsScreen`, add state for allModels and import dialog:
```kotlin
val allModels by modelRepository.allModels.collectAsState(initial = ModelManifest.models)
var showImportDialog by remember { mutableStateOf(false) }
```

Replace `items(ModelManifest.models)` (line 85) with `items(allModels)`.

**Step 2: Add Custom badge to ModelCard**

In the `ModelCard` composable, add an `isCustom: Boolean` parameter. After the display name text, show a badge:
```kotlin
if (isCustom) {
    Spacer(modifier = Modifier.width(8.dp))
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = "Custom",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
```

Also show `languageHint` if present on custom models (below the "Active" text):
```kotlin
model.languageHint?.let { lang ->
    Text(
        text = WhisperLanguages.displayName(lang),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

**Step 3: Add "Import Model" card after the model list**

After the `items(allModels)` block, add:
```kotlin
item {
    OutlinedCard(
        onClick = { showImportDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+ Import Model",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
```

**Step 4: Wire up the ImportModelDialog**

At the bottom of `SettingsScreen` (after the delete dialog), add the import dialog:
```kotlin
if (showImportDialog) {
    ImportModelDialog(
        onDismiss = { showImportDialog = false },
        onImportFile = { displayName, languageHint ->
            showImportDialog = false
            onBrowseFile(displayName, languageHint)
        },
        onImportUrl = { url, displayName, languageHint ->
            showImportDialog = false
            scope.launch {
                val result = modelRepository.importFromUrl(url, displayName, languageHint)
                result.onFailure { e ->
                    snackbarHostState.showSnackbar(
                        "Import failed: ${e.message ?: "Unknown error"}"
                    )
                }
                result.onSuccess {
                    snackbarHostState.showSnackbar("Model imported successfully")
                }
            }
        },
        selectedFileName = pendingFileName,
        onBrowseFile = onPickFile,
    )
}
```

This requires new parameters on `SettingsScreen`:
```kotlin
fun SettingsScreen(
    // ... existing params ...
    onPickFile: () -> Unit = {},
    pendingFileName: String? = null,
    onBrowseFile: (displayName: String, languageHint: String?) -> Unit = { _, _ -> },
)
```

**Step 5: Pass isCustom to ModelCard in the items() call**

Update the `ModelCard` call to pass `isCustom = model.isCustom` and add `model` as a parameter for language hint display.

**Step 6: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt
git commit -m "feat: show custom models + import button in SettingsScreen"
```

---

### Task 6: Wire file picker in SettingsActivity

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`

**Step 1: Register OpenDocument contract and manage state**

Add state variables and the file picker launcher:
```kotlin
private val pendingFileName = mutableStateOf<String?>(null)
private var pendingUri: android.net.Uri? = null
private var pendingImportName: String? = null
private var pendingImportLanguage: String? = null

private val filePickerLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) {
        pendingUri = uri
        // Extract display name from URI for the dialog
        val cursor = contentResolver.query(uri, null, null, null, null)
        val name = cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
        }
        pendingFileName.value = name ?: uri.lastPathSegment ?: "unknown.bin"
    }
}
```

**Step 2: Pass callbacks to SettingsScreen**

In `setContent`, add the new parameters:
```kotlin
SettingsScreen(
    // ... existing params ...
    onPickFile = {
        filePickerLauncher.launch(arrayOf("*/*"))
    },
    pendingFileName = pendingFileName.value,
    onBrowseFile = { displayName, languageHint ->
        val uri = pendingUri ?: return@SettingsScreen
        pendingUri = null
        pendingFileName.value = null
        kotlinx.coroutines.MainScope().launch {
            val result = repository.importFromFile(uri, displayName, languageHint)
            // Snackbar is handled inside SettingsScreen
        }
    },
)
```

Wait — the import result needs to surface back to the SettingsScreen snackbar. Better approach: let SettingsScreen handle the import call directly by passing the URI.

Revised approach — add a `pendingUri` state that SettingsScreen can use:

```kotlin
fun SettingsScreen(
    // ... existing params ...
    onPickFile: () -> Unit = {},
    pendingFileName: String? = null,
    pendingUri: android.net.Uri? = null,
    onImportComplete: () -> Unit = {},
)
```

Then in SettingsScreen, the `onImportFile` callback in the dialog does:
```kotlin
onImportFile = { displayName, languageHint ->
    showImportDialog = false
    val uri = pendingUri ?: return@ImportModelDialog
    scope.launch {
        val result = modelRepository.importFromFile(uri, displayName, languageHint)
        onImportComplete()
        result.onFailure { e ->
            snackbarHostState.showSnackbar("Import failed: ${e.message ?: "Unknown error"}")
        }
        result.onSuccess {
            snackbarHostState.showSnackbar("Model imported successfully")
        }
    }
},
```

And in SettingsActivity:
```kotlin
SettingsScreen(
    // ... existing params ...
    onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
    pendingFileName = pendingFileName.value,
    pendingUri = pendingUri.value,
    onImportComplete = {
        pendingUri.value = null
        pendingFileName.value = null
    },
)
```

Change `pendingUri` to `mutableStateOf<android.net.Uri?>(null)` so Compose observes it.

**Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt
git commit -m "feat: wire OpenDocument file picker for custom model import"
```

---

### Task 7: Fix WhisperBoardIME to resolve custom models

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt`

**Step 1: No changes needed**

`WhisperBoardIME` already calls `modelRepository.getActiveModelPath()` which was updated in Task 2 to resolve both built-in and custom models. Verify by reading the code — the IME watches `activeModelName` and calls `getActiveModelPath()`, which now uses `findModel()` to look up custom models.

**Step 2: Verify full build**

Run: `./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`
Expected: BUILD SUCCESSFUL

**Step 3: Commit (only if changes were needed)**

No commit needed for this task.

---

### Task 8: Final integration verification

**Step 1: Full build**

Run: `./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

**Step 2: Manual test checklist**

Install on device/emulator and verify:
- [ ] Settings shows built-in models (tiny, base, small) as before
- [ ] "Import Model" card appears at bottom of model list
- [ ] Tapping opens import dialog with File/URL tabs
- [ ] File tab: Browse button opens file picker
- [ ] URL tab: text field accepts a URL
- [ ] Display name field is required
- [ ] Language dropdown shows all whisper languages + "Auto-detect"
- [ ] File import copies .bin and shows in model list with "Custom" badge
- [ ] URL import downloads and shows in model list with "Custom" badge
- [ ] Custom model can be selected as active ("Use" button)
- [ ] Custom model can be deleted ("Delete" button)
- [ ] Keyboard works with custom model selected
- [ ] Invalid file shows error snackbar
- [ ] App restart preserves custom models
