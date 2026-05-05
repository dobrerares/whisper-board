package com.whisperboard.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Metadata for a single small instruction-tuned model. Sibling of
 * [ModelInfo]; deliberately separate per the Slice 4 brief because LLMs and
 * Whisper models have different size classes and different relevant
 * metadata. Trying to share would force a lowest-common-denominator schema.
 */
data class LlmModelInfo(
    val name: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String = "",
    /** Approximate parameter count in billions, for sizing decisions. */
    val parameterBillions: Float = 0f,
    /** Quantization label, e.g. `"Q4_K_M"`, `"Q5_K_M"`. Free-form string. */
    val quantization: String = "",
    /** Trained context length in tokens. The runtime clips to this on init. */
    val contextLength: Int = 0,
    val isCustom: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("displayName", displayName)
        put("fileName", fileName)
        put("url", url)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("parameterBillions", parameterBillions.toDouble())
        put("quantization", quantization)
        put("contextLength", contextLength)
    }

    companion object {
        fun fromJson(json: JSONObject): LlmModelInfo = LlmModelInfo(
            name = json.getString("name"),
            displayName = json.getString("displayName"),
            fileName = json.getString("fileName"),
            url = json.optString("url", ""),
            sizeBytes = json.optLong("sizeBytes", 0L),
            sha256 = json.optString("sha256", ""),
            parameterBillions = json.optDouble("parameterBillions", 0.0).toFloat(),
            quantization = json.optString("quantization", ""),
            contextLength = json.optInt("contextLength", 0),
            isCustom = true,
        )

        fun listToJson(models: List<LlmModelInfo>): String =
            JSONArray(models.map { it.toJson() }).toString()

        fun listFromJson(json: String): List<LlmModelInfo> {
            if (json.isBlank()) return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
