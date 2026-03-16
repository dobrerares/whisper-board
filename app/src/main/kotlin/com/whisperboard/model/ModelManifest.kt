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
            sha256 = "bd577a113a864445d4c299885e0cb97d4ba92b5f"
        ),
        ModelInfo(
            name = "base",
            displayName = "Base (~142 MB)",
            fileName = "ggml-base.bin",
            url = "$BASE_URL/ggml-base.bin",
            sizeBytes = 142_000_000L,
            sha256 = "465707469ff3a37a2b9b8d8f89f2f99de7299b5d"
        ),
        ModelInfo(
            name = "small",
            displayName = "Small (~466 MB)",
            fileName = "ggml-small.bin",
            url = "$BASE_URL/ggml-small.bin",
            sizeBytes = 466_000_000L,
            sha256 = "55356645c2b361a969dfd0ef2c5a50d530afd8d5"
        ),
    )

    fun getByName(name: String): ModelInfo? = models.find { it.name == name }
}
