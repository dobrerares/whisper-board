package com.whisperboard.whisper

import android.util.Log

class WhisperLib {
    companion object {
        private const val TAG = "WhisperLib"

        init {
            System.loadLibrary("whisper_jni")
            Log.d(TAG, "Loaded whisper_jni")
        }
    }

    external fun initContextFromFile(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(contextPtr: Long, audioData: FloatArray, language: String)
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
}
