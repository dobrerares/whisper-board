# Phase 2: Model Management — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let users download, select, and delete Whisper GGML models from within the app, replacing the current hardcoded `ggml-tiny.bin` path.

**Architecture:** Three new files in `:app` — `ModelInfo`/`ModelManifest` (hardcoded model catalog), `ModelRepository` (download/verify/delete via OkHttp, progress via Flow, metadata in DataStore), and a Compose settings UI. The IME service reads the active model from DataStore and loads it dynamically.

**Tech Stack:** OkHttp 4.12.0 (downloads), DataStore 1.1.1 (preferences), Kotlin Coroutines (async), Compose Material3 (UI). All already in the version catalog but not yet imported.

---

## Current State

- Model path is hardcoded: `File(filesDir, "models/ggml-tiny.bin")` in `WhisperBoardIME.kt:63`
- `WhisperContext` is loaded once in `onCreate()` and set on the ViewModel
- OkHttp and DataStore are defined in `gradle/libs.versions.toml` but not in `app/build.gradle.kts` dependencies
- No settings UI exists — `LanguageChip.kt` has a placeholder `onClick`
- No `SettingsActivity` exists

## Build & Run

```bash
# Build (from project root, inside nix devshell or with Android SDK)
nix develop --command bash -c './gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2'

# Or if ANDROID_HOME is available directly:
./gradlew :app:assembleDebug

# CI builds automatically on push to main
```

---

### Task 1: Add OkHttp and DataStore dependencies

**Files:**
- Modify: `app/build.gradle.kts` (add 2 dependency lines)

**Step 1: Add dependencies**

In `app/build.gradle.kts`, add these two lines inside the `dependencies` block (after the existing `implementation` lines, before `debugImplementation`):

```kotlin
implementation(libs.okhttp)
implementation(libs.androidx.datastore.preferences)
```

These are already defined in `gradle/libs.versions.toml` at lines 22 and 24.

**Step 2: Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "Add OkHttp and DataStore dependencies for model management"
```

---

### Task 2: Create ModelInfo and ModelManifest

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/model/ModelInfo.kt`
- Create: `app/src/main/kotlin/com/whisperboard/model/ModelManifest.kt`

**Step 1: Create ModelInfo data class**

Create `app/src/main/kotlin/com/whisperboard/model/ModelInfo.kt`:

```kotlin
package com.whisperboard.model

data class ModelInfo(
    val name: String,          // e.g. "tiny", "base", "small"
    val displayName: String,   // e.g. "Tiny (75 MB)"
    val fileName: String,      // e.g. "ggml-tiny.bin"
    val url: String,           // HuggingFace download URL
    val sizeBytes: Long,       // approximate file size
    val sha256: String,        // SHA256 hash for verification
)
```

**Step 2: Create ModelManifest with hardcoded model list**

Create `app/src/main/kotlin/com/whisperboard/model/ModelManifest.kt`:

```kotlin
package com.whisperboard.model

object ModelManifest {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val models = listOf(
        ModelInfo(
            name = "tiny",
            displayName = "Tiny (~75 MB)",
            fileName = "ggml-tiny.bin",
            url = "$BASE_URL/ggml-tiny.bin",
            sizeBytes = 75_000_000L,
            sha256 = "bd577a113a864445d4c299885e0cb97d4ba92b5f"
        ),
        ModelInfo(
            name = "base",
            displayName = "Base (~142 MB)",
            fileName = "ggml-base.bin",
            url = "$BASE_URL/ggml-base.bin",
            sizeBytes = 142_000_000L,
            sha256 = "465707469ff3a37a2b9b8d8f89f2f99de7299b5d"
        ),
        ModelInfo(
            name = "small",
            displayName = "Small (~466 MB)",
            fileName = "ggml-small.bin",
            url = "$BASE_URL/ggml-small.bin",
            sizeBytes = 466_000_000L,
            sha256 = "55356645c2b361a969dfd0ef2c5a50d530afd8d5"
        ),
    )

    fun getByName(name: String): ModelInfo? = models.find { it.name == name }
}
```

> **Note:** The sha256 values above are placeholders. The implementer should look up the actual SHA256 hashes from the HuggingFace repo (`https://huggingface.co/ggerganov/whisper.cpp/tree/main`) or compute them after downloading. If exact hashes aren't easily available, use empty strings and skip verification for now — it can be added later.

**Step 3: Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/
git commit -m "Add ModelInfo and ModelManifest with Whisper GGML model catalog"
```

---

### Task 3: Create ModelRepository (download, verify, delete)

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt`

**Step 1: Create ModelRepository**

Create `app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt`:

```kotlin
package com.whisperboard.model

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_prefs")

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
}

class ModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "ModelRepository"
        private val KEY_DOWNLOADED = stringSetPreferencesKey("downloaded_models")
        private val KEY_ACTIVE = stringPreferencesKey("active_model")
    }

    private val client = OkHttpClient()
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

    // --- Download state ---

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: Flow<DownloadProgress?> = _downloadProgress

    private val _downloadingModel = MutableStateFlow<String?>(null)
    val downloadingModel: Flow<String?> = _downloadingModel

    // --- Preferences ---

    val downloadedModels: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOADED] ?: emptySet()
    }

    val activeModelName: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE]
    }

    suspend fun setActiveModel(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE] = name
        }
    }

    // --- File operations ---

    fun getModelFile(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: ModelInfo): Boolean = getModelFile(model).exists()

    suspend fun getActiveModelPath(): String? {
        val name = context.dataStore.data.first()[KEY_ACTIVE] ?: return null
        val model = ModelManifest.getByName(name) ?: return null
        val file = getModelFile(model)
        return if (file.exists()) file.absolutePath else null
    }

    // --- Download ---

    suspend fun download(model: ModelInfo): Result<File> = withContext(Dispatchers.IO) {
        val file = getModelFile(model)
        val tempFile = File(modelsDir, "${model.fileName}.tmp")

        try {
            _downloadingModel.value = model.name
            _downloadProgress.value = DownloadProgress(0, model.sizeBytes)

            Log.d(TAG, "Downloading ${model.name} from ${model.url}")

            val request = Request.Builder().url(model.url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response"))
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        _downloadProgress.value = DownloadProgress(bytesRead, totalBytes)
                    }
                }
            }

            // Verify SHA256 if provided
            if (model.sha256.isNotEmpty()) {
                val hash = sha256(tempFile)
                if (hash != model.sha256) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("SHA256 mismatch: expected ${model.sha256}, got $hash")
                    )
                }
            }

            tempFile.renameTo(file)

            // Update preferences
            context.dataStore.edit { prefs ->
                val current = prefs[KEY_DOWNLOADED] ?: emptySet()
                prefs[KEY_DOWNLOADED] = current + model.name
            }

            Log.d(TAG, "Downloaded ${model.name} (${file.length()} bytes)")
            Result.success(file)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${model.name}", e)
            tempFile.delete()
            Result.failure(e)
        } finally {
            _downloadingModel.value = null
            _downloadProgress.value = null
        }
    }

    // --- Delete ---

    suspend fun delete(model: ModelInfo) {
        withContext(Dispatchers.IO) {
            getModelFile(model).delete()
        }
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DOWNLOADED] ?: emptySet()
            prefs[KEY_DOWNLOADED] = current - model.name

            // Clear active if this was the active model
            if (prefs[KEY_ACTIVE] == model.name) {
                prefs.remove(KEY_ACTIVE)
            }
        }
        Log.d(TAG, "Deleted ${model.name}")
    }

    // --- Util ---

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

**Step 2: Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/model/ModelRepository.kt
git commit -m "Add ModelRepository with download, verify, and delete support"
```

---

### Task 4: Create Settings UI (model management screen)

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`
- Create: `app/src/main/kotlin/com/whisperboard/settings/ModelManagerScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add SettingsActivity + INTERNET permission)

**Step 1: Add INTERNET permission and SettingsActivity to manifest**

Modify `app/src/main/AndroidManifest.xml`. Add `INTERNET` permission alongside the existing `RECORD_AUDIO`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

Add the activity inside the `<application>` tag (before or after the `<service>` element):

```xml
<activity
    android:name=".settings.SettingsActivity"
    android:label="Whisper Board Settings"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

> **Note:** Making SettingsActivity a LAUNCHER activity also solves the Phase 1 limitation of having no way to request RECORD_AUDIO permission — the activity can handle that too.

Also update the IME `<meta-data>` to point to the settings activity. In `app/src/main/res/xml/method.xml`, add `android:settingsActivity="com.whisperboard.settings.SettingsActivity"` to the `<input-method>` tag.

**Step 2: Create ModelManagerScreen composable**

Create `app/src/main/kotlin/com/whisperboard/settings/ModelManagerScreen.kt`:

```kotlin
package com.whisperboard.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.whisperboard.model.DownloadProgress
import com.whisperboard.model.ModelInfo
import com.whisperboard.model.ModelManifest
import com.whisperboard.model.ModelRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(repository: ModelRepository) {
    val scope = rememberCoroutineScope()
    val downloadedModels by repository.downloadedModels.collectAsState(initial = emptySet())
    val activeModelName by repository.activeModelName.collectAsState(initial = null)
    val downloadingModel by repository.downloadingModel.collectAsState(initial = null)
    val downloadProgress by repository.downloadProgress.collectAsState(initial = null)

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
                        scope.launch { repository.download(model) }
                    },
                    onDelete = {
                        scope.launch { repository.delete(model) }
                    },
                    onSelect = {
                        scope.launch { repository.setActiveModel(model.name) }
                    },
                )
            }
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

**Step 3: Create SettingsActivity**

Create `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`:

```kotlin
package com.whisperboard.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.whisperboard.model.ModelRepository

class SettingsActivity : ComponentActivity() {

    private lateinit var repository: ModelRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled implicitly — permission is now granted or denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ModelRepository(applicationContext)

        // Request RECORD_AUDIO permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme {
                ModelManagerScreen(repository = repository)
            }
        }
    }
}
```

**Step 4: Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/ app/src/main/AndroidManifest.xml app/src/main/res/xml/method.xml
git commit -m "Add model management settings UI with download/delete/select"
```

---

### Task 5: Wire dynamic model selection to IME

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt`

**Step 1: Replace hardcoded model path with DataStore-backed selection**

In `WhisperBoardIME.kt`, change the model loading in `onCreate()` from the hardcoded path to reading from `ModelRepository`:

Current code (lines 62-77 approximately):
```kotlin
val modelPath = File(filesDir, "models/ggml-tiny.bin").absolutePath
```

Replace the model loading block with:

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

This means:
- If the user has downloaded and selected a model via Settings, it loads that model
- If no model is selected, mic shows "[No model loaded]" (existing behavior)
- The hardcoded `ggml-tiny.bin` path is removed

Also add import for `ModelRepository`:
```kotlin
import com.whisperboard.model.ModelRepository
```

**Step 2: Verify build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt
git commit -m "Wire dynamic model selection from DataStore to IME service"
```

---

### Task 6: Push and verify CI

**Step 1: Push to main**

```bash
git push origin main
```

**Step 2: Verify CI passes**

```bash
gh run watch --exit-status
```

Expected: CI green, all steps pass.

---

## Verification Checklist

After all tasks are complete, verify on a real device or emulator:

1. Install APK: `./gradlew :app:installDebug`
2. Open "Whisper Board Settings" from the app launcher
3. RECORD_AUDIO permission prompt should appear on first launch
4. Model list shows Tiny, Base, Small with "Download" buttons
5. Download Tiny model — progress bar shows, completes successfully
6. Tap "Use" on downloaded model — card highlights as active
7. Switch to a text field, select Whisper Board keyboard
8. Tap mic → speak → tap mic → transcription appears (using selected model)
9. Go back to Settings, download Base model, switch to it
10. Transcribe again — works with the new model
11. Delete Tiny model — file removed, UI updates
12. Delete active model — falls back to "[No model loaded]" state on next IME restart
