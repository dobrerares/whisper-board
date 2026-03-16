package com.whisperboard.whisper

class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    external fun initContextFromFile(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(contextPtr: Long, audioData: FloatArray, language: String)
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
}
