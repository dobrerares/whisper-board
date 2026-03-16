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
