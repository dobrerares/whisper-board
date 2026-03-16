package com.whisperboard.transcription

import com.whisperboard.whisper.WhisperContext

class LocalEngine(private val ctx: WhisperContext) : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, language: String): String =
        ctx.transcribe(samples, language).joinToString(" ") { it.text.trim() }

    override fun close() = ctx.close()
}
