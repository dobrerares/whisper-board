package com.whisperboard.postprocessing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * [PostProcessor] backed by an OpenAI-compatible `/v1/chat/completions`
 * endpoint. Reuses the `ApiProvider` / `ApiSettingsRepository` config (base
 * URL, API key) so users do not re-enter endpoint details for the polish
 * stage.
 *
 * Response parsing follows the standard
 * `{ "choices": [ { "message": { "content": "..." } } ] }` shape. Any
 * deviation (network error, non-2xx, malformed body) raises
 * [PostProcessingException]; [PostProcessingRouter] catches that and falls
 * back to the raw transcript silently.
 */
open class ApiPostProcessor(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : PostProcessor {

    override suspend fun polish(rawTranscript: String, context: PostProcessingContext): String =
        withContext(Dispatchers.IO) {
            if (rawTranscript.isBlank()) return@withContext rawTranscript

            val prompts = PromptBuilder.build(
                rawTranscript = rawTranscript,
                languageProfile = context.languageProfile,
                tier = PostProcessingTier.TIER_2,
            )

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", prompts.system)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompts.user)
                    })
                })
                put("temperature", 0.2)
            }.toString()

            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = try {
                client.newCall(requestBuilder.build()).execute()
            } catch (e: Exception) {
                throw PostProcessingException("Network error: ${e.message}", e)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body?.string().orEmpty()
                    throw PostProcessingException("API error ${resp.code}: $errorBody")
                }

                val bodyString = resp.body?.string()
                    ?: throw PostProcessingException("Empty response body")

                parseContent(bodyString)
            }
        }

    private fun parseContent(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw PostProcessingException("Malformed response: ${e.message}", e)
        }
        val choices = json.optJSONArray("choices")
            ?: throw PostProcessingException("Malformed response: missing 'choices'")
        if (choices.length() == 0) {
            throw PostProcessingException("Malformed response: empty 'choices'")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw PostProcessingException("Malformed response: missing 'message'")
        val content = message.optString("content", "")
        if (content.isBlank()) {
            throw PostProcessingException("Malformed response: empty 'content'")
        }
        return content.trim()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
