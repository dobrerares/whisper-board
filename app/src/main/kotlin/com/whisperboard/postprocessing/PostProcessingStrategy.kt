package com.whisperboard.postprocessing

/**
 * Routing strategy for the post-processing stage. Mirrors `EngineStrategy` so
 * the user-facing vocabulary is identical to transcription routing.
 *
 * Slice 1 only routes [OFF] and [API_ONLY]. The `LOCAL_*` values are defined
 * so settings UI and persistence are forward-compatible, but
 * [PostProcessingRouter] falls back to passing the raw transcript through
 * (effectively [OFF]) until the local runtime lands in slice #4.
 */
enum class PostProcessingStrategy {
    /** No post-processing — raw transcript is returned as-is. */
    OFF,

    /** Always use the remote `/v1/chat/completions` runtime. */
    API_ONLY,

    /** Local SLM only. Routable in slice #4; falls back to OFF until then. */
    LOCAL_ONLY,

    /** Local first, fall back to API on timeout. Routable in slice #4. */
    LOCAL_PREFERRED,

    /** API when online, local when offline. Routable in slice #4. */
    API_WHEN_ONLINE,
}
