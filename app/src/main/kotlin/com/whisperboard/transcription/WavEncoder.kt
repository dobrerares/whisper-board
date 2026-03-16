package com.whisperboard.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a FloatArray of normalized audio samples [-1, 1] into a WAV byte array.
 * Output format: 16kHz, mono, 16-bit PCM.
 */
object WavEncoder {

    private const val SAMPLE_RATE = 16000
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16

    fun encode(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2 // 16-bit = 2 bytes per sample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(CHANNELS.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8) // byte rate
        buffer.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
        buffer.putShort(BITS_PER_SAMPLE.toShort())

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            buffer.putShort((clamped * 32767).toInt().toShort())
        }

        return buffer.array()
    }
}
