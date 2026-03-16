package com.whisperboard.whisper

import android.os.Build
import android.util.Log
import java.io.File

class WhisperLib {
    companion object {
        private const val TAG = "WhisperLib"

        init {
            loadNativeLibrary()
        }

        private fun loadNativeLibrary() {
            val cpuInfo = try {
                File("/proc/cpuinfo").readText()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read /proc/cpuinfo", e)
                ""
            }

            if (Build.SUPPORTED_ABIS[0] == "arm64-v8a") {
                if (cpuInfo.contains("fphp") && cpuInfo.contains("asimdhp")) {
                    try {
                        System.loadLibrary("whisper_v8fp16_va")
                        Log.d(TAG, "Loaded whisper_v8fp16_va (arm64 FP16 optimized)")
                        return
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "Failed to load whisper_v8fp16_va, falling back", e)
                    }
                }
            } else if (Build.SUPPORTED_ABIS[0] == "armeabi-v7a") {
                if (cpuInfo.contains("vfpv4")) {
                    try {
                        System.loadLibrary("whisper_vfpv4")
                        Log.d(TAG, "Loaded whisper_vfpv4 (armv7 VFPv4 optimized)")
                        return
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "Failed to load whisper_vfpv4, falling back", e)
                    }
                }
            }

            System.loadLibrary("whisper_jni")
            Log.d(TAG, "Loaded whisper_jni (default)")
        }
    }

    external fun initContextFromFile(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun fullTranscribe(contextPtr: Long, audioData: FloatArray, language: String)
    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
}
