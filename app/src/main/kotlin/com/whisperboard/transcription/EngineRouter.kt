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
