package com.whisperboard.model

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

/**
 * Repository for LLM weights. Sibling of [ModelRepository], deliberately
 * separate per the Slice 4 brief because LLMs and Whisper models have
 * different size classes, different metadata, and a separate active-model
 * picker. The two repositories share only the underlying DataStore (which
 * keeps prefs in one file) and the download/import patterns.
 *
 * Storage layout: weights live under `filesDir/llm-models/`. Whisper models
 * live under `filesDir/models/` so the two are physically separated and a
 * future "delete all transcription models" or "delete all polish models"
 * affordance can be implemented per-repo.
 */
class LlmModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "LlmModelRepository"
        private val KEY_DOWNLOADED = stringSetPreferencesKey("llm_downloaded_models")
        private val KEY_ACTIVE = stringPreferencesKey("llm_active_model")
        private val KEY_CUSTOM_MODELS = stringPreferencesKey("llm_custom_models")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    private val modelsDir = File(context.filesDir, "llm-models").also { it.mkdirs() }

    @Volatile
    private var activeCall: okhttp3.Call? = null

    // --- Download state ---

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: Flow<DownloadProgress?> = _downloadProgress

    private val _downloadingModel = MutableStateFlow<String?>(null)
    val downloadingModel: Flow<String?> = _downloadingModel

    // --- Preferences ---

    val downloadedModels: Flow<Set<String>> = context.appDataStore.data.map { prefs ->
        prefs[KEY_DOWNLOADED] ?: emptySet()
    }

    val activeModelName: Flow<String?> = context.appDataStore.data.map { prefs ->
        prefs[KEY_ACTIVE]
    }

    val customModels: Flow<List<LlmModelInfo>> = context.appDataStore.data.map { prefs ->
        val json = prefs[KEY_CUSTOM_MODELS] ?: ""
        LlmModelInfo.listFromJson(json)
    }

    val allModels: Flow<List<LlmModelInfo>> = customModels.map { custom ->
        LlmModelManifest.models + custom
    }

    suspend fun setActiveModel(name: String) {
        context.appDataStore.edit { prefs -> prefs[KEY_ACTIVE] = name }
    }

    suspend fun clearActiveModel() {
        context.appDataStore.edit { prefs -> prefs.remove(KEY_ACTIVE) }
    }

    // --- File operations ---

    fun getModelFile(model: LlmModelInfo): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: LlmModelInfo): Boolean = getModelFile(model).exists()

    suspend fun getActiveModel(): LlmModelInfo? {
        val prefs = context.appDataStore.data.first()
        val name = prefs[KEY_ACTIVE] ?: return null
        return findModel(name, prefs[KEY_CUSTOM_MODELS] ?: "")
    }

    suspend fun getActiveModelPath(): String? {
        val model = getActiveModel() ?: return null
        val file = getModelFile(model)
        return if (file.exists()) file.absolutePath else null
    }

    private fun findModel(name: String, customJson: String): LlmModelInfo? {
        LlmModelManifest.getByName(name)?.let { return it }
        return LlmModelInfo.listFromJson(customJson).find { it.name == name }
    }

    // --- Download ---

    suspend fun download(model: LlmModelInfo): Result<File> = withContext(Dispatchers.IO) {
        val file = getModelFile(model)
        val tempFile = File(modelsDir, "${model.fileName}.tmp")

        try {
            _downloadingModel.value = model.name
            _downloadProgress.value = DownloadProgress(0, model.sizeBytes)

            Log.d(TAG, "Downloading ${model.name} from ${model.url}")

            val request = Request.Builder().url(model.url).build()
            val call = client.newCall(request)
            activeCall = call
            val response = call.execute()

            if (!response.isSuccessful) {
                response.close()
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

            if (model.sha256.isNotEmpty()) {
                val hash = sha256(tempFile)
                if (hash != model.sha256) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("SHA256 mismatch: expected ${model.sha256}, got $hash")
                    )
                }
            }

            if (!tempFile.renameTo(file)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Failed to move downloaded file into place"))
            }

            context.appDataStore.edit { prefs ->
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
            activeCall = null
            _downloadingModel.value = null
            _downloadProgress.value = null
        }
    }

    fun cancelDownload() {
        activeCall?.cancel()
        activeCall = null
    }

    // --- Delete ---

    suspend fun delete(model: LlmModelInfo) {
        withContext(Dispatchers.IO) {
            getModelFile(model).delete()
        }
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_DOWNLOADED] ?: emptySet()
            prefs[KEY_DOWNLOADED] = current - model.name

            if (prefs[KEY_ACTIVE] == model.name) {
                prefs.remove(KEY_ACTIVE)
            }

            if (model.isCustom) {
                val customJson = prefs[KEY_CUSTOM_MODELS] ?: ""
                val remaining = LlmModelInfo.listFromJson(customJson)
                    .filter { it.name != model.name }
                prefs[KEY_CUSTOM_MODELS] = LlmModelInfo.listToJson(remaining)
            }
        }
        Log.d(TAG, "Deleted ${model.name}")
    }

    // --- Import (file URI) ---

    suspend fun importFromFile(
        uri: android.net.Uri,
        displayName: String,
    ): Result<LlmModelInfo> = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val fileName = "custom_${timestamp}.gguf"
        val destFile = File(modelsDir, fileName)

        try {
            val totalBytes = context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L

            _downloadingModel.value = "import"
            _downloadProgress.value = DownloadProgress(0, totalBytes)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        _downloadProgress.value = DownloadProgress(bytesRead, totalBytes)
                    }
                }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            val model = LlmModelInfo(
                name = "custom_$timestamp",
                displayName = displayName,
                fileName = fileName,
                url = "",
                sizeBytes = destFile.length(),
                isCustom = true,
            )

            context.appDataStore.edit { prefs ->
                val customJson = prefs[KEY_CUSTOM_MODELS] ?: ""
                val current = LlmModelInfo.listFromJson(customJson)
                prefs[KEY_CUSTOM_MODELS] = LlmModelInfo.listToJson(current + model)
                prefs[KEY_DOWNLOADED] = (prefs[KEY_DOWNLOADED] ?: emptySet()) + model.name
            }

            Result.success(model)
        } catch (e: Exception) {
            destFile.delete()
            Result.failure(e)
        } finally {
            _downloadingModel.value = null
            _downloadProgress.value = null
        }
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
