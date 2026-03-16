package com.whisperboard.transcription

sealed interface TranscriptionEngine {
    suspend fun transcribe(samples: FloatArray, language: String): String
    fun close() {}
}
