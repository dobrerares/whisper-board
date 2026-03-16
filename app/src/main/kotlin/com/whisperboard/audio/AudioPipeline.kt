package com.whisperboard.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAVEFORM_EMIT_INTERVAL_MS = 100L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var accumulatedSamples = mutableListOf<Short>()

    private val _waveformData = MutableStateFlow(FloatArray(0))
    val waveformData: StateFlow<FloatArray> = _waveformData.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording(scope: CoroutineScope) {
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return
        }

        if (!hasRecordPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            return
        }

        val bufferSize = minBufferSize * 4

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        accumulatedSamples = mutableListOf()
        _isRecording.value = true

        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(minBufferSize)
            var lastWaveformEmit = System.currentTimeMillis()

            while (isActive && _isRecording.value) {
                val readCount = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break

                if (readCount > 0) {
                    synchronized(accumulatedSamples) {
                        for (i in 0 until readCount) {
                            accumulatedSamples.add(readBuffer[i])
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastWaveformEmit >= WAVEFORM_EMIT_INTERVAL_MS) {
                        lastWaveformEmit = now
                        val chunk = readBuffer.copyOfRange(0, readCount)
                        _waveformData.value = shortsToFloats(chunk)
                    }
                }
            }
        }
    }

    suspend fun stopRecording(): FloatArray {
        if (!_isRecording.value) {
            Log.w(TAG, "Not currently recording")
            return FloatArray(0)
        }

        _isRecording.value = false
        recordingJob?.join()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val samples: FloatArray
        synchronized(accumulatedSamples) {
            samples = shortsToFloats(accumulatedSamples.toShortArray())
            accumulatedSamples.clear()
        }

        _waveformData.value = FloatArray(0)

        Log.d(TAG, "Recording stopped. Captured ${samples.size} samples " +
                "(${samples.size.toFloat() / SAMPLE_RATE}s)")

        return samples
    }

    private fun shortsToFloats(shorts: ShortArray): FloatArray {
        return FloatArray(shorts.size) { i ->
            shorts[i].toFloat() / Short.MAX_VALUE.toFloat()
        }
    }

    private fun MutableList<Short>.toShortArray(): ShortArray {
        val array = ShortArray(size)
        forEachIndexed { index, value -> array[index] = value }
        return array
    }
}
