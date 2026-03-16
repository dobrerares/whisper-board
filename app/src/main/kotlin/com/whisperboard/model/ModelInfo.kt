package com.whisperboard.model

data class ModelInfo(
    val name: String,          // e.g. "tiny", "base", "small"
    val displayName: String,   // e.g. "Tiny (75 MB)"
    val fileName: String,      // e.g. "ggml-tiny.bin"
    val url: String,           // HuggingFace download URL
    val sizeBytes: Long,       // approximate file size
    val sha256: String,        // SHA256 hash for verification
)
