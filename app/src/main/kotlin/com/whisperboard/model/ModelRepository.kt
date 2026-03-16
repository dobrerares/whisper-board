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
        private val KEY_CUSTOM_MODELS = stringPreferencesKey("custom_models")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .build()
    private val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }

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

    val customModels: Flow<List<ModelInfo>> = context.appDataStore.data.map { prefs ->
        val json = prefs[KEY_CUSTOM_MODELS] ?: ""
        ModelInfo.listFromJson(json)
    }

    val allModels: Flow<List<ModelInfo>> = customModels.map { custom ->
        ModelManifest.models + custom
    }

    suspend fun setActiveModel(name: String) {
        context.appDataStore.edit { prefs ->
            prefs[KEY_ACTIVE] = name
        }
    }

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

    // --- File operations ---

    fun getModelFile(model: ModelInfo): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: ModelInfo): Boolean = getModelFile(model).exists()

    suspend fun getActiveModelPath(): String? {
        val prefs = context.appDataStore.data.first()
        val name = prefs[KEY_ACTIVE] ?: return null
        val model = findModel(name, prefs) ?: return null
        val file = getModelFile(model)
        return if (file.exists()) file.absolutePath else null
    }

    private fun findModel(name: String, prefs: androidx.datastore.preferences.core.Preferences): ModelInfo? {
        ModelManifest.getByName(name)?.let { return it }
        val json = prefs[KEY_CUSTOM_MODELS] ?: ""
        return ModelInfo.listFromJson(json).find { it.name == name }
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

            if (!tempFile.renameTo(file)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Failed to move downloaded file into place"))
            }

            // Update preferences
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

    // --- Import ---

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
        val updated = result.map { model.copy(sizeBytes = it.length()) }
        if (updated.isSuccess) {
            addCustomModel(updated.getOrThrow())
        }
        return updated
    }

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
