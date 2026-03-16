package com.whisperboard.model

import org.json.JSONArray
import org.json.JSONObject

data class ModelInfo(
    val name: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val isCustom: Boolean = false,
    val languageHint: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("displayName", displayName)
        put("fileName", fileName)
        put("url", url)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("languageHint", languageHint ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelInfo = ModelInfo(
            name = json.getString("name"),
            displayName = json.getString("displayName"),
            fileName = json.getString("fileName"),
            url = json.optString("url", ""),
            sizeBytes = json.optLong("sizeBytes", 0),
            sha256 = json.optString("sha256", ""),
            isCustom = true,
            languageHint = json.optString("languageHint").takeIf { it.isNotEmpty() && it != "null" },
        )

        fun listToJson(models: List<ModelInfo>): String =
            JSONArray(models.map { it.toJson() }).toString()

        fun listFromJson(json: String): List<ModelInfo> {
            if (json.isBlank()) return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
