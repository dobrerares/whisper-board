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
