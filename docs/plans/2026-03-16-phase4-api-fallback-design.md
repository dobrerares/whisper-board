# Phase 4: API Fallback — Design

## Overview

Add cloud API transcription as an alternative/fallback to on-device Whisper inference. Users configure a provider (OpenAI, Groq, self-hosted, custom) and a strategy that controls when local vs. API transcription is used.

## Architecture

### TranscriptionEngine sealed interface

New `transcription/` package in `:app`.

```kotlin
sealed interface TranscriptionEngine {
    suspend fun transcribe(samples: FloatArray, language: String): String
    fun close() {}
}
```

Returns `String` directly (segments already joined). `close()` default no-op; only LocalEngine needs cleanup.

### LocalEngine

Wraps existing `WhisperContext`:

```kotlin
class LocalEngine(private val ctx: WhisperContext) : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, language: String): String =
        ctx.transcribe(samples, language).joinToString(" ") { it.text.trim() }
    override fun close() = ctx.close()
}
```

### ApiEngine

Sends audio as WAV to OpenAI-compatible `/v1/audio/transcriptions`:

```kotlin
class ApiEngine(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : TranscriptionEngine
```

- Converts `FloatArray` (16kHz mono) → WAV bytes in memory
- POST multipart/form-data with fields: `file` (WAV), `model`, `language`
- Parses JSON response `{"text": "..."}` field
- Uses existing OkHttp dependency

### ApiProvider enum

```kotlin
enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isCustomUrl: Boolean = false,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1/", "whisper-1", false),
    GROQ("Groq", "https://api.groq.com/openai/v1/", "whisper-large-v3-turbo", false),
    SELF_HOSTED("Self-hosted", "", "", true),
    CUSTOM("Custom", "", "", true),
}
```

- OPENAI/GROQ: URL and default model baked in, user provides API key
- SELF_HOSTED: user provides URL, no auth
- CUSTOM: user provides URL, model name, optional API key

### EngineStrategy enum

```kotlin
enum class EngineStrategy {
    LOCAL_ONLY,      // Always on-device, fail if no model
    API_ONLY,        // Always cloud API
    LOCAL_PREFERRED, // Try local, fall back to API if timeout exceeded
    API_WHEN_ONLINE, // Use API when network available, local when offline
}
```

### EngineRouter

Created in `WhisperBoardIME`, passed to `KeyboardViewModel`.

```kotlin
class EngineRouter(
    private val settingsRepository: ApiSettingsRepository,
    private val connectivityManager: ConnectivityManager,
) {
    var localEngine: LocalEngine? = null
    var apiEngine: ApiEngine? = null

    suspend fun transcribe(samples: FloatArray, language: String): String
}
```

Strategy logic:
- **LOCAL_ONLY**: Use local engine, fail if unavailable
- **API_ONLY**: Use API engine, fail if unconfigured
- **LOCAL_PREFERRED**: Try local with `withTimeoutOrNull(fallbackTimeout)`, fall back to API on timeout/failure
- **API_WHEN_ONLINE**: Check `ConnectivityManager` for connectivity; API if online, local if offline; no cross-fallback

### ApiSettingsRepository

| Key | Storage | Default |
|-----|---------|---------|
| `api_provider` | DataStore | `OPENAI` |
| `api_key` | EncryptedSharedPreferences | `""` |
| `api_base_url` | DataStore | `""` |
| `api_model` | DataStore | `""` |
| `engine_strategy` | DataStore | `LOCAL_ONLY` |
| `fallback_timeout_seconds` | DataStore | `15` |

All exposed as `Flow<T>` following existing repository pattern. API key stored in EncryptedSharedPreferences (Jetpack Security).

## Settings UI

New "Transcription" section in SettingsScreen between Models and Languages:

- **Strategy picker**: dropdown for 4 strategies (default LOCAL_ONLY)
- **API Provider**: dropdown (OpenAI, Groq, Self-hosted, Custom). Shown when strategy ≠ LOCAL_ONLY
- **API Key**: password field. Shown for OpenAI/Groq/Custom
- **Base URL**: text field. Shown for Self-hosted/Custom
- **Model name**: text field. Shown for Custom only
- **Fallback timeout**: slider 5–30s (default 15). Shown for LOCAL_PREFERRED only

Fields appear/disappear based on strategy and provider selection.

## ViewModel Changes

Replace `whisperContext: WhisperContext?` with `engineRouter: EngineRouter`. The transcription call becomes:

```kotlin
val text = engineRouter.transcribe(samples, activeLanguage.value)
_transcribedText.value = text
```

Pre-recording guard checks `engineRouter.canTranscribe()` instead of null-checking WhisperContext.

## IME Lifecycle Changes

`WhisperBoardIME.onCreate()`:
- Creates `ApiSettingsRepository` and `EngineRouter`
- Existing `activeModelName.collectLatest` sets `router.localEngine = LocalEngine(ctx)`
- New flow watches API settings, rebuilds `router.apiEngine` when config changes
- Passes `engineRouter` to `KeyboardViewModel` instead of calling `setWhisperContext()`

## Dependencies

New: `androidx.security:security-crypto` for EncryptedSharedPreferences. OkHttp already present.

## Not in scope

- `:core` module extraction (deferred until there's a second consumer)
- API key validation/test button
- Streaming/incremental API transcription
- Usage tracking or cost estimation
