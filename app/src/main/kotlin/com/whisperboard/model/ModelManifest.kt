package com.whisperboard.model

object ModelManifest {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val models = listOf(
        ModelInfo(
            name = "tiny",
            displayName = "Tiny (~75 MB)",
            fileName = "ggml-tiny.bin",
            url = "$BASE_URL/ggml-tiny.bin",
            sizeBytes = 75_000_000L,
            sha256 = ""
        ),
        ModelInfo(
            name = "base",
            displayName = "Base (~142 MB)",
            fileName = "ggml-base.bin",
            url = "$BASE_URL/ggml-base.bin",
            sizeBytes = 142_000_000L,
            sha256 = ""
        ),
        ModelInfo(
            name = "small",
            displayName = "Small (~466 MB)",
            fileName = "ggml-small.bin",
            url = "$BASE_URL/ggml-small.bin",
            sizeBytes = 466_000_000L,
            sha256 = ""
        ),
    )

    fun getByName(name: String): ModelInfo? = models.find { it.name == name }
}
