#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "whisper.h"

#define UNUSED(x) (void)(x)
#define TAG "WhisperJNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static inline int min_int(int a, int b) {
    return (a < b) ? a : b;
}

JNIEXPORT jlong JNICALL
Java_com_whisperboard_whisper_WhisperLib_initContextFromFile(
        JNIEnv *env, jobject thiz, jstring model_path) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path, NULL);
    LOGI("Loading model from: %s", model_path_chars);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    if (context == NULL) {
        LOGE("Failed to load model from: %s", model_path_chars);
    }
    (*env)->ReleaseStringUTFChars(env, model_path, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whisperboard_whisper_WhisperLib_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context != NULL) {
        whisper_free(context);
    }
}

JNIEXPORT void JNICALL
Java_com_whisperboard_whisper_WhisperLib_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray audio_data, jstring language) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // Get language string
    const char *language_chars = (*env)->GetStringUTFChars(env, language, NULL);

    // Set up default params with greedy sampling
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = true;
    params.print_special    = false;
    params.translate        = false;
    params.no_context       = true;
    params.single_segment   = false;
    params.offset_ms        = 0;

    // Set language: "auto" means auto-detect, otherwise use the specified language
    if (strcmp(language_chars, "auto") == 0) {
        params.language = NULL;  // NULL triggers auto-detection
    } else {
        params.language = language_chars;
    }

    // Set thread count: min(4, available CPUs)
    int cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    params.n_threads = min_int(4, cpu_count);

    LOGI("Transcribing %d samples, language=%s, threads=%d",
         audio_data_length, language_chars, params.n_threads);

    whisper_reset_timings(context);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGE("Failed to run whisper_full");
    } else {
        whisper_print_timings(context);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language, language_chars);
}

JNIEXPORT jint JNICALL
Java_com_whisperboard_whisper_WhisperLib_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

JNIEXPORT jstring JNICALL
Java_com_whisperboard_whisper_WhisperLib_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}
