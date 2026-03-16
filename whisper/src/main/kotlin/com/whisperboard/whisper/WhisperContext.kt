package com.whisperboard.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

class WhisperContext private constructor(private val modelPath: String) : Closeable {

    private val whisperLib = WhisperLib()
    private var contextPtr: Long = 0L
    private val mutex = Mutex()

    data class Segment(val text: String)

    private fun initContext() {
        contextPtr = whisperLib.initContextFromFile(modelPath)
        require(contextPtr != 0L) { "Failed to initialize Whisper context from: $modelPath" }
    }

    suspend fun transcribe(samples: FloatArray, language: String = "auto"): List<Segment> {
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                check(contextPtr != 0L) { "Whisper context is not initialized or has been closed" }
                whisperLib.fullTranscribe(contextPtr, samples, language)
                val segmentCount = whisperLib.getTextSegmentCount(contextPtr)
                (0 until segmentCount).map { i ->
                    Segment(text = whisperLib.getTextSegment(contextPtr, i))
                }
            }
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                if (contextPtr != 0L) {
                    whisperLib.freeContext(contextPtr)
                    contextPtr = 0L
                }
            }
        }
    }

    companion object {
        suspend fun createContext(modelPath: String): WhisperContext {
            return withContext(Dispatchers.IO) {
                WhisperContext(modelPath).also { it.initContext() }
            }
        }
    }
}
