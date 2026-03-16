package com.whisperboard.transcription

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.whisperboard.model.appDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiSettingsRepository(private val context: Context) {

    companion object {
        private const val TAG = "ApiSettingsRepository"
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
            else -> if (customModel.isNotBlank()) customModel else prov.defaultModel
        }

        if (effectiveUrl.isBlank()) return null

        return ResolvedApiConfig(
            baseUrl = effectiveUrl,
            apiKey = key,
            model = effectiveModel,
        )
    }

    // --- Model discovery ---

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchAvailableModels(
        providerOverride: ApiProvider? = null,
        baseUrlOverride: String? = null,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val prov = providerOverride ?: provider.first()
        val url = (baseUrlOverride ?: if (prov.isCustomUrl) baseUrl.first() else prov.baseUrl)
            .trimEnd('/') + "/models"
        val key = getApiKey()

        if (url.isBlank() || url == "/models") {
            return@withContext Result.failure(Exception("No base URL configured"))
        }

        try {
            val requestBuilder = Request.Builder().url(url).get()
            if (key.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $key")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val json = JSONObject(response.body!!.string())
            val data = json.getJSONArray("data")
            val allIds = (0 until data.length()).map {
                data.getJSONObject(it).getString("id")
            }

            val filtered = when (prov) {
                ApiProvider.OPENAI, ApiProvider.GROQ ->
                    allIds.filter { it.contains("whisper", ignoreCase = true) }
                ApiProvider.SELF_HOSTED, ApiProvider.CUSTOM ->
                    allIds
            }.sorted()

            Result.success(filtered)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch models from $url", e)
            Result.failure(e)
        }
    }

}
