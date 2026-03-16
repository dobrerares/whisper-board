# Phase 4: API Fallback Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add cloud API transcription as an alternative/fallback to on-device Whisper, with user-configurable strategy and provider presets.

**Architecture:** New `transcription/` package with `TranscriptionEngine` sealed interface, `LocalEngine` wrapping WhisperContext, `ApiEngine` for OpenAI-compatible HTTP, `EngineRouter` with 4 strategies, `ApiSettingsRepository` for config persistence. ViewModel and IME updated to use router instead of raw WhisperContext.

**Tech Stack:** Kotlin, OkHttp 4.12.0 (existing), EncryptedSharedPreferences (new dep: `androidx.security:security-crypto`), DataStore (existing), Jetpack Compose Material3 (existing)

---

### Task 1: Add security-crypto dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Step 1: Add version and library to version catalog**

In `gradle/libs.versions.toml`, add version:

```toml
securityCrypto = "1.0.0"
```

And library entry:

```toml
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
```

**Step 2: Add dependency to app module**

In `app/build.gradle.kts`, add after the `okhttp` line:

```kotlin
implementation(libs.androidx.security.crypto)
```

**Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add security-crypto dependency for encrypted API key storage"
```

---

### Task 2: Create TranscriptionEngine interface and LocalEngine

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/transcription/TranscriptionEngine.kt`
- Create: `app/src/main/kotlin/com/whisperboard/transcription/LocalEngine.kt`

**Step 1: Create TranscriptionEngine sealed interface**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/TranscriptionEngine.kt
package com.whisperboard.transcription

sealed interface TranscriptionEngine {
    suspend fun transcribe(samples: FloatArray, language: String): String
    fun close() {}
}
```

**Step 2: Create LocalEngine**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/LocalEngine.kt
package com.whisperboard.transcription

import com.whisperboard.whisper.WhisperContext

class LocalEngine(private val ctx: WhisperContext) : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, language: String): String =
        ctx.transcribe(samples, language).joinToString(" ") { it.text.trim() }

    override fun close() = ctx.close()
}
```

**Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/transcription/
git commit -m "feat: add TranscriptionEngine interface and LocalEngine"
```

---

### Task 3: Create WavEncoder utility

ApiEngine needs to convert the `FloatArray` (16kHz mono normalized [-1,1]) into WAV bytes for the multipart upload. This is a pure function with no dependencies.

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/transcription/WavEncoder.kt`

**Step 1: Create WavEncoder**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/WavEncoder.kt
package com.whisperboard.transcription

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a FloatArray of normalized audio samples [-1, 1] into a WAV byte array.
 * Output format: 16kHz, mono, 16-bit PCM.
 */
object WavEncoder {

    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    fun encode(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2 // 16-bit = 2 bytes per sample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8) // byte rate
        buffer.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            buffer.putShort((clamped * 32767).toInt().toShort())
        }

        return buffer.array()
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/transcription/WavEncoder.kt
git commit -m "feat: add WavEncoder for FloatArray to WAV conversion"
```

---

### Task 4: Create ApiProvider enum and ApiEngine

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/transcription/ApiProvider.kt`
- Create: `app/src/main/kotlin/com/whisperboard/transcription/ApiEngine.kt`

**Step 1: Create ApiProvider**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/ApiProvider.kt
package com.whisperboard.transcription

enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isCustomUrl: Boolean = false,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1/", "whisper-1"),
    GROQ("Groq", "https://api.groq.com/openai/v1/", "whisper-large-v3-turbo"),
    SELF_HOSTED("Self-hosted", "", "", isCustomUrl = true),
    CUSTOM("Custom", "", "", isCustomUrl = true),
}
```

**Step 2: Create ApiEngine**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/ApiEngine.kt
package com.whisperboard.transcription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiEngine(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TranscriptionEngine {

    override suspend fun transcribe(samples: FloatArray, language: String): String =
        withContext(Dispatchers.IO) {
            val wavBytes = WavEncoder.encode(samples)

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", model)

            if (language != "auto") {
                bodyBuilder.addFormDataPart("language", language)
            }

            val url = baseUrl.trimEnd('/') + "/audio/transcriptions"

            val requestBuilder = Request.Builder()
                .url(url)
                .post(bodyBuilder.build())

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                response.close()
                throw TranscriptionException("API error ${response.code}: $errorBody")
            }

            val json = JSONObject(response.body!!.string())
            json.getString("text").trim()
        }
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

Note: Uses `org.json.JSONObject` which is part of the Android SDK (no extra dependency needed).

**Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/transcription/ApiProvider.kt app/src/main/kotlin/com/whisperboard/transcription/ApiEngine.kt
git commit -m "feat: add ApiProvider presets and ApiEngine for cloud transcription"
```

---

### Task 5: Create EngineStrategy and ApiSettingsRepository

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/transcription/EngineStrategy.kt`
- Create: `app/src/main/kotlin/com/whisperboard/transcription/ApiSettingsRepository.kt`

**Step 1: Create EngineStrategy**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/EngineStrategy.kt
package com.whisperboard.transcription

enum class EngineStrategy {
    LOCAL_ONLY,
    API_ONLY,
    LOCAL_PREFERRED,
    API_WHEN_ONLINE,
}
```

**Step 2: Create ApiSettingsRepository**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/ApiSettingsRepository.kt
package com.whisperboard.transcription

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ApiSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_PROVIDER = stringPreferencesKey("api_provider")
        private val KEY_BASE_URL = stringPreferencesKey("api_base_url")
        private val KEY_MODEL = stringPreferencesKey("api_model")
        private val KEY_STRATEGY = stringPreferencesKey("engine_strategy")
        private val KEY_FALLBACK_TIMEOUT = intPreferencesKey("fallback_timeout_seconds")

        private const val ENCRYPTED_PREFS_NAME = "api_secrets"
        private const val KEY_API_KEY = "api_key"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS_NAME,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // --- Flows ---

    val provider: Flow<ApiProvider> = context.appDataStore.data.map { prefs ->
        val name = prefs[KEY_PROVIDER] ?: ApiProvider.OPENAI.name
        runCatching { ApiProvider.valueOf(name) }.getOrDefault(ApiProvider.OPENAI)
    }

    val baseUrl: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_BASE_URL] ?: ""
    }

    val model: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_MODEL] ?: ""
    }

    val engineStrategy: Flow<EngineStrategy> = context.appDataStore.data.map { prefs ->
        val name = prefs[KEY_STRATEGY] ?: EngineStrategy.LOCAL_ONLY.name
        runCatching { EngineStrategy.valueOf(name) }.getOrDefault(EngineStrategy.LOCAL_ONLY)
    }

    val fallbackTimeoutSeconds: Flow<Int> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FALLBACK_TIMEOUT] ?: 15
    }

    // --- API Key (EncryptedSharedPreferences — not a Flow, read synchronously) ---

    fun getApiKey(): String = encryptedPrefs.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(key: String) {
        encryptedPrefs.edit().putString(KEY_API_KEY, key).apply()
    }

    // --- Setters ---

    suspend fun setProvider(provider: ApiProvider) {
        context.appDataStore.edit { it[KEY_PROVIDER] = provider.name }
    }

    suspend fun setBaseUrl(url: String) {
        context.appDataStore.edit { it[KEY_BASE_URL] = url }
    }

    suspend fun setModel(model: String) {
        context.appDataStore.edit { it[KEY_MODEL] = model }
    }

    suspend fun setEngineStrategy(strategy: EngineStrategy) {
        context.appDataStore.edit { it[KEY_STRATEGY] = strategy.name }
    }

    suspend fun setFallbackTimeout(seconds: Int) {
        context.appDataStore.edit { it[KEY_FALLBACK_TIMEOUT] = seconds }
    }

    // --- Resolve effective config ---

    data class ResolvedApiConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    /**
     * Resolves the effective API config from provider + overrides.
     * Returns null if required fields are missing.
     */
    suspend fun resolveApiConfig(): ResolvedApiConfig? {
        val prefs = context.appDataStore.data.map { it }.let { flow ->
            kotlinx.coroutines.flow.first(flow)
        }
        val prov = provider.let { kotlinx.coroutines.flow.first(it) }
        val customUrl = baseUrl.let { kotlinx.coroutines.flow.first(it) }
        val customModel = model.let { kotlinx.coroutines.flow.first(it) }
        val key = getApiKey()

        val effectiveUrl = if (prov.isCustomUrl) customUrl else prov.baseUrl
        val effectiveModel = when (prov) {
            ApiProvider.CUSTOM -> customModel.ifBlank { return null }
            ApiProvider.SELF_HOSTED -> customModel.ifBlank { "default" }
            else -> prov.defaultModel
        }

        if (effectiveUrl.isBlank()) return null

        return ResolvedApiConfig(
            baseUrl = effectiveUrl,
            apiKey = key,
            model = effectiveModel,
        )
    }
}

// Helper — kotlinx.coroutines.flow.first is an extension on Flow
private suspend fun <T> kotlinx.coroutines.flow.first(flow: Flow<T>): T =
    kotlinx.coroutines.flow.firstOrNull(flow) ?: throw NoSuchElementException()
```

Wait — the helper at the bottom is wrong. Let me fix that. `kotlinx.coroutines.flow.first` is already available as `Flow<T>.first()`. The `resolveApiConfig` method should use proper imports. Let me revise:

**Step 2 revised: Create ApiSettingsRepository (corrected)**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/ApiSettingsRepository.kt
package com.whisperboard.transcription

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ApiSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_PROVIDER = stringPreferencesKey("api_provider")
        private val KEY_BASE_URL = stringPreferencesKey("api_base_url")
        private val KEY_MODEL = stringPreferencesKey("api_model")
        private val KEY_STRATEGY = stringPreferencesKey("engine_strategy")
        private val KEY_FALLBACK_TIMEOUT = intPreferencesKey("fallback_timeout_seconds")

        private const val ENCRYPTED_PREFS_NAME = "api_secrets"
        private const val KEY_API_KEY = "api_key"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS_NAME,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val provider: Flow<ApiProvider> = context.appDataStore.data.map { prefs ->
        val name = prefs[KEY_PROVIDER] ?: ApiProvider.OPENAI.name
        runCatching { ApiProvider.valueOf(name) }.getOrDefault(ApiProvider.OPENAI)
    }

    val baseUrl: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_BASE_URL] ?: ""
    }

    val model: Flow<String> = context.appDataStore.data.map { prefs ->
        prefs[KEY_MODEL] ?: ""
    }

    val engineStrategy: Flow<EngineStrategy> = context.appDataStore.data.map { prefs ->
        val name = prefs[KEY_STRATEGY] ?: EngineStrategy.LOCAL_ONLY.name
        runCatching { EngineStrategy.valueOf(name) }.getOrDefault(EngineStrategy.LOCAL_ONLY)
    }

    val fallbackTimeoutSeconds: Flow<Int> = context.appDataStore.data.map { prefs ->
        prefs[KEY_FALLBACK_TIMEOUT] ?: 15
    }

    fun getApiKey(): String = encryptedPrefs.getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(key: String) {
        encryptedPrefs.edit().putString(KEY_API_KEY, key).apply()
    }

    suspend fun setProvider(provider: ApiProvider) {
        context.appDataStore.edit { it[KEY_PROVIDER] = provider.name }
    }

    suspend fun setBaseUrl(url: String) {
        context.appDataStore.edit { it[KEY_BASE_URL] = url }
    }

    suspend fun setModel(model: String) {
        context.appDataStore.edit { it[KEY_MODEL] = model }
    }

    suspend fun setEngineStrategy(strategy: EngineStrategy) {
        context.appDataStore.edit { it[KEY_STRATEGY] = strategy.name }
    }

    suspend fun setFallbackTimeout(seconds: Int) {
        context.appDataStore.edit { it[KEY_FALLBACK_TIMEOUT] = seconds }
    }

    data class ResolvedApiConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    suspend fun resolveApiConfig(): ResolvedApiConfig? {
        val prov = provider.first()
        val customUrl = baseUrl.first()
        val customModel = model.first()
        val key = getApiKey()

        val effectiveUrl = if (prov.isCustomUrl) customUrl else prov.baseUrl
        val effectiveModel = when (prov) {
            ApiProvider.CUSTOM -> customModel.ifBlank { return null }
            ApiProvider.SELF_HOSTED -> customModel.ifBlank { "default" }
            else -> prov.defaultModel
        }

        if (effectiveUrl.isBlank()) return null

        return ResolvedApiConfig(
            baseUrl = effectiveUrl,
            apiKey = key,
            model = effectiveModel,
        )
    }
}
```

**Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/transcription/EngineStrategy.kt \
        app/src/main/kotlin/com/whisperboard/transcription/ApiSettingsRepository.kt
git commit -m "feat: add EngineStrategy and ApiSettingsRepository"
```

---

### Task 6: Create EngineRouter

**Files:**
- Create: `app/src/main/kotlin/com/whisperboard/transcription/EngineRouter.kt`

**Step 1: Create EngineRouter**

```kotlin
// app/src/main/kotlin/com/whisperboard/transcription/EngineRouter.kt
package com.whisperboard.transcription

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class EngineRouter(
    private val settingsRepository: ApiSettingsRepository,
    private val connectivityManager: ConnectivityManager,
) {
    companion object {
        private const val TAG = "EngineRouter"
    }

    @Volatile var localEngine: LocalEngine? = null
    @Volatile var apiEngine: ApiEngine? = null

    suspend fun transcribe(samples: FloatArray, language: String): String {
        val strategy = settingsRepository.engineStrategy.first()
        return when (strategy) {
            EngineStrategy.LOCAL_ONLY -> transcribeLocal(samples, language)
            EngineStrategy.API_ONLY -> transcribeApi(samples, language)
            EngineStrategy.LOCAL_PREFERRED -> transcribeLocalWithFallback(samples, language)
            EngineStrategy.API_WHEN_ONLINE -> transcribeByConnectivity(samples, language)
        }
    }

    fun canTranscribe(): Boolean {
        // At least one engine must be available
        return localEngine != null || apiEngine != null
    }

    fun close() {
        localEngine?.close()
        localEngine = null
        apiEngine = null
    }

    private suspend fun transcribeLocal(samples: FloatArray, language: String): String {
        val engine = localEngine
            ?: throw TranscriptionException("No local model loaded — download one in Settings")
        return engine.transcribe(samples, language)
    }

    private suspend fun transcribeApi(samples: FloatArray, language: String): String {
        val engine = apiEngine
            ?: throw TranscriptionException("API not configured — check Settings")
        return engine.transcribe(samples, language)
    }

    private suspend fun transcribeLocalWithFallback(
        samples: FloatArray,
        language: String,
    ): String {
        val timeoutMs = settingsRepository.fallbackTimeoutSeconds.first() * 1000L
        val local = localEngine

        if (local != null) {
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    local.transcribe(samples, language)
                }
                if (result != null) return result
                Log.w(TAG, "Local transcription timed out after ${timeoutMs}ms, falling back to API")
            } catch (e: Exception) {
                Log.w(TAG, "Local transcription failed, falling back to API", e)
            }
        }

        // Fall back to API
        val api = apiEngine
            ?: throw TranscriptionException(
                if (local == null) "No local model loaded and API not configured"
                else "Local transcription failed and API not configured"
            )
        return api.transcribe(samples, language)
    }

    private suspend fun transcribeByConnectivity(
        samples: FloatArray,
        language: String,
    ): String {
        return if (isOnline() && apiEngine != null) {
            transcribeApi(samples, language)
        } else {
            transcribeLocal(samples, language)
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/transcription/EngineRouter.kt
git commit -m "feat: add EngineRouter with strategy-based transcription dispatch"
```

---

### Task 7: Wire EngineRouter into KeyboardViewModel

Replace `whisperContext: WhisperContext?` with `engineRouter: EngineRouter` in the ViewModel.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt`

**Step 1: Refactor KeyboardViewModel**

Changes to make:
1. Remove `whisperContext` field and `setWhisperContext()` method
2. Add `engineRouter` field with `setEngineRouter()` method
3. Update `toggleRecording()` to use `engineRouter.transcribe()`
4. Update pre-recording guard to check `engineRouter.canTranscribe()`
5. Update `cleanup()` to close the router
6. Remove `import com.whisperboard.whisper.WhisperContext`

The full updated file:

```kotlin
package com.whisperboard.ui

import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.LanguageRepository
import com.whisperboard.transcription.EngineRouter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class EditAction {
    data object Backspace : EditAction()
    data object Comma : EditAction()
    data object Space : EditAction()
    data object Period : EditAction()
    data object Enter : EditAction()
}

class KeyboardViewModel(
    private val audioPipeline: AudioPipeline,
    private val languageRepository: LanguageRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "KeyboardViewModel"
    }

    @Volatile
    private var engineRouter: EngineRouter? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _transcribedText = MutableStateFlow("")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    val activeLanguage: StateFlow<String> = languageRepository.activeLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    val favoriteLanguages: StateFlow<Set<String>> = languageRepository.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    val waveformData: StateFlow<FloatArray> = audioPipeline.waveformData

    var currentImeAction: Int = EditorInfo.IME_ACTION_DONE

    fun setEngineRouter(router: EngineRouter?) {
        engineRouter = router
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            _isRecording.value = false
            viewModelScope.launch {
                try {
                    val samples = audioPipeline.stopRecording()
                    if (samples.isEmpty()) {
                        Log.w(TAG, "No audio samples captured")
                        return@launch
                    }

                    val router = engineRouter
                    if (router == null || !router.canTranscribe()) {
                        _errorMessage.tryEmit("No transcription engine available")
                        Log.w(TAG, "EngineRouter unavailable or no engine configured")
                        return@launch
                    }

                    _isProcessing.value = true
                    val text = router.transcribe(samples, activeLanguage.value)
                    _transcribedText.value = text
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription failed", e)
                    _errorMessage.tryEmit(e.message ?: "Transcription failed")
                } finally {
                    _isProcessing.value = false
                }
            }
        } else {
            val router = engineRouter
            if (router == null || !router.canTranscribe()) {
                _errorMessage.tryEmit("No transcription engine available")
                Log.w(TAG, "EngineRouter unavailable or no engine configured")
                return
            }

            if (!audioPipeline.hasRecordPermission()) {
                _errorMessage.tryEmit("Microphone permission required")
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                return
            }

            audioPipeline.startRecording(viewModelScope)
            _isRecording.value = true
        }
    }

    fun commitText(inputConnection: InputConnection?) {
        val text = _transcribedText.value
        if (text.isNotEmpty() && inputConnection != null) {
            inputConnection.commitText(text, 1)
            _transcribedText.value = ""
        }
    }

    fun onEditAction(action: EditAction, inputConnection: InputConnection?) {
        inputConnection ?: return
        when (action) {
            EditAction.Backspace -> inputConnection.deleteSurroundingText(1, 0)
            EditAction.Comma -> inputConnection.commitText(",", 1)
            EditAction.Space -> inputConnection.commitText(" ", 1)
            EditAction.Period -> inputConnection.commitText(".", 1)
            EditAction.Enter -> {
                if (!inputConnection.performEditorAction(currentImeAction)) {
                    inputConnection.commitText("\n", 1)
                }
            }
        }
    }

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

    fun cleanup() {
        engineRouter = null
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/ui/KeyboardViewModel.kt
git commit -m "refactor: replace WhisperContext with EngineRouter in KeyboardViewModel"
```

---

### Task 8: Wire EngineRouter into WhisperBoardIME

Update IME to create EngineRouter, manage engine lifecycle, and wire API settings.

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt`

**Step 1: Update WhisperBoardIME**

Changes:
1. Create `ApiSettingsRepository` and `EngineRouter` in `onCreate()`
2. Existing `activeModelName.collectLatest` sets `router.localEngine` instead of calling `setWhisperContext()`
3. Add a new flow that watches API settings and rebuilds `apiEngine`
4. Pass router to ViewModel via `setEngineRouter()`
5. Close router in `onDestroy()`

Full updated file:

```kotlin
package com.whisperboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.whisperboard.audio.AudioPipeline
import com.whisperboard.model.LanguageRepository
import com.whisperboard.model.ModelRepository
import com.whisperboard.transcription.ApiEngine
import com.whisperboard.transcription.ApiSettingsRepository
import com.whisperboard.transcription.EngineRouter
import com.whisperboard.transcription.LocalEngine
import com.whisperboard.ui.KeyboardScreen
import com.whisperboard.ui.KeyboardViewModel
import com.whisperboard.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WhisperBoardIME : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "WhisperBoardIME"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var audioPipeline: AudioPipeline
    private lateinit var languageRepository: LanguageRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var apiSettingsRepository: ApiSettingsRepository
    private lateinit var engineRouter: EngineRouter
    private lateinit var viewModel: KeyboardViewModel

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        audioPipeline = AudioPipeline(this)
        languageRepository = LanguageRepository(applicationContext)
        modelRepository = ModelRepository(applicationContext)
        apiSettingsRepository = ApiSettingsRepository(applicationContext)

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        engineRouter = EngineRouter(apiSettingsRepository, connectivityManager)

        viewModel = KeyboardViewModel(audioPipeline, languageRepository)
        viewModel.setEngineRouter(engineRouter)

        // Watch active model → update local engine
        serviceScope.launch {
            modelRepository.activeModelName.collectLatest { modelName ->
                try {
                    engineRouter.localEngine?.close()
                    engineRouter.localEngine = null

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
                    engineRouter.localEngine = LocalEngine(ctx)
                    Log.d(TAG, "Whisper model loaded: $modelName")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load Whisper model", e)
                }
            }
        }

        // Watch API settings → rebuild API engine
        serviceScope.launch {
            combine(
                apiSettingsRepository.provider,
                apiSettingsRepository.baseUrl,
                apiSettingsRepository.model,
            ) { provider, baseUrl, model -> Triple(provider, baseUrl, model) }
                .collectLatest {
                    try {
                        val config = apiSettingsRepository.resolveApiConfig()
                        engineRouter.apiEngine = if (config != null) {
                            ApiEngine(apiClient, config.baseUrl, config.apiKey, config.model)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to configure API engine", e)
                        engineRouter.apiEngine = null
                    }
                }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (info != null) {
            viewModel.currentImeAction =
                info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val locale = newSubtype?.languageTag
            ?: newSubtype?.locale
            ?: ""
        val langCode = if (locale.isEmpty()) "auto" else locale.split("-").first().lowercase()
        Log.d(TAG, "IME subtype changed: $langCode")
        serviceScope.launch {
            languageRepository.setActiveLanguage(langCode)
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val ime = this
        return ComposeKeyboardView(this) {
            KeyboardScreen(
                viewModel = viewModel,
                inputConnection = { ime.currentInputConnection }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        viewModel.cleanup()
        engineRouter.close()
        audioPipeline.release()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }

    private inner class ComposeKeyboardView(
        context: Context,
        private val content: @Composable () -> Unit
    ) : AbstractComposeView(context) {

        @Composable
        override fun Content() {
            content()
        }

        override fun onAttachedToWindow() {
            val ime = this@WhisperBoardIME
            setViewTreeLifecycleOwner(ime)
            setViewTreeViewModelStoreOwner(ime)
            setViewTreeSavedStateRegistryOwner(ime)
            super.onAttachedToWindow()
        }
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/WhisperBoardIME.kt
git commit -m "feat: wire EngineRouter into WhisperBoardIME lifecycle"
```

---

### Task 9: Add Transcription settings section to SettingsScreen

**Files:**
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt`

**Step 1: Update SettingsActivity to pass ApiSettingsRepository**

Add `apiSettingsRepository` parameter:

```kotlin
// In SettingsActivity.onCreate(), after languageRepository:
val apiSettingsRepository = ApiSettingsRepository(applicationContext)

// Update setContent:
setContent {
    MaterialTheme {
        SettingsScreen(
            modelRepository = repository,
            languageRepository = languageRepository,
            apiSettingsRepository = apiSettingsRepository,
        )
    }
}
```

**Step 2: Add new section to SettingsScreen**

Add `apiSettingsRepository: ApiSettingsRepository` parameter to `SettingsScreen()`.

Add a new "Transcription" section between Models and Languages. Insert this block after the Models `items()` call and before the Languages `item`:

```kotlin
// --- Transcription section ---
item {
    Text(
        text = "Transcription",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

item {
    TranscriptionSettingsSection(apiSettingsRepository = apiSettingsRepository)
}
```

**Step 3: Create TranscriptionSettingsSection composable**

Add to `SettingsScreen.kt` (or create a separate file if it's large — but keeping it in the same file follows the existing pattern):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionSettingsSection(
    apiSettingsRepository: ApiSettingsRepository,
) {
    val scope = rememberCoroutineScope()
    val strategy by apiSettingsRepository.engineStrategy.collectAsState(initial = EngineStrategy.LOCAL_ONLY)
    val provider by apiSettingsRepository.provider.collectAsState(initial = ApiProvider.OPENAI)
    val customUrl by apiSettingsRepository.baseUrl.collectAsState(initial = "")
    val customModel by apiSettingsRepository.model.collectAsState(initial = "")
    val fallbackTimeout by apiSettingsRepository.fallbackTimeoutSeconds.collectAsState(initial = 15)
    var apiKey by remember { mutableStateOf(apiSettingsRepository.getApiKey()) }

    // Strategy selector
    Text("Engine Strategy", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(4.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        EngineStrategy.entries.forEachIndexed { index, s ->
            SegmentedButton(
                selected = strategy == s,
                onClick = { scope.launch { apiSettingsRepository.setEngineStrategy(s) } },
                shape = SegmentedButtonDefaults.itemShape(index, EngineStrategy.entries.size),
            ) {
                Text(
                    text = when (s) {
                        EngineStrategy.LOCAL_ONLY -> "Local"
                        EngineStrategy.API_ONLY -> "API"
                        EngineStrategy.LOCAL_PREFERRED -> "Local+API"
                        EngineStrategy.API_WHEN_ONLINE -> "Auto"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    // Strategy description
    Text(
        text = when (strategy) {
            EngineStrategy.LOCAL_ONLY -> "Always use on-device model"
            EngineStrategy.API_ONLY -> "Always use cloud API"
            EngineStrategy.LOCAL_PREFERRED -> "Try local first, fall back to API on timeout"
            EngineStrategy.API_WHEN_ONLINE -> "Use API when online, local when offline"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )

    // API settings (hidden when LOCAL_ONLY)
    if (strategy != EngineStrategy.LOCAL_ONLY) {
        // Provider picker
        var providerExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = providerExpanded,
            onExpandedChange = { providerExpanded = it },
        ) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = providerExpanded,
                onDismissRequest = { providerExpanded = false },
            ) {
                ApiProvider.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.displayName) },
                        onClick = {
                            scope.launch { apiSettingsRepository.setProvider(p) }
                            providerExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // API Key (shown for providers that need auth)
        if (provider != ApiProvider.SELF_HOSTED) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    apiSettingsRepository.setApiKey(it)
                },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Custom URL (shown for SELF_HOSTED and CUSTOM)
        if (provider.isCustomUrl) {
            OutlinedTextField(
                value = customUrl,
                onValueChange = { scope.launch { apiSettingsRepository.setBaseUrl(it) } },
                label = { Text("Base URL") },
                placeholder = { Text("https://your-server.com/v1/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Custom model name (shown for CUSTOM only)
        if (provider == ApiProvider.CUSTOM) {
            OutlinedTextField(
                value = customModel,
                onValueChange = { scope.launch { apiSettingsRepository.setModel(it) } },
                label = { Text("Model Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Fallback timeout (shown for LOCAL_PREFERRED only)
        if (strategy == EngineStrategy.LOCAL_PREFERRED) {
            Text(
                text = "Fallback timeout: ${fallbackTimeout}s",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = fallbackTimeout.toFloat(),
                onValueChange = {
                    scope.launch { apiSettingsRepository.setFallbackTimeout(it.toInt()) }
                },
                valueRange = 5f..30f,
                steps = 24,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
```

**Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add app/src/main/kotlin/com/whisperboard/settings/SettingsScreen.kt \
        app/src/main/kotlin/com/whisperboard/settings/SettingsActivity.kt
git commit -m "feat: add Transcription settings UI with strategy and provider config"
```

---

### Task 10: Full build verification

**Step 1: Run full debug build**

Run: `./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`
Expected: BUILD SUCCESSFUL

**Step 2: Commit any fixes if needed, then push**

```bash
git push origin main
```

---

### Task 11: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Update architecture section**

Add `transcription/` to the key packages list:
```
- `transcription/` — TranscriptionEngine, LocalEngine, ApiEngine, EngineRouter, ApiSettingsRepository
```

**Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with transcription package"
```

---

### Task 12: Tag v0.1.0 and push

**Step 1: Create annotated tag**

```bash
git tag -a v0.1.0 -m "v0.1.0: MVP with local + API transcription, model management, full keyboard UI"
```

**Step 2: Push tag**

```bash
git push origin main --tags
```

**Step 3: Verify CI creates GitHub Release**

Check: `gh run list --limit 3` to see the release workflow trigger.
Then: `gh release view v0.1.0` to confirm the release was created with the APK.
