package com.whisperboard.transcription

enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val isCustomUrl: Boolean = false,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1/", "whisper-1"),
    GROQ("Groq", "https://api.groq.com/openai/v1/", "whisper-large-v3-turbo"),
    SELF_HOSTED("Self-hosted", "", "", isCustomUrl = true),
    CUSTOM("Custom", "", "", isCustomUrl = true),
}
