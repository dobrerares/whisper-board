package com.whisperboard.model

/**
 * Stock catalogue of small instruction-tuned models suitable for the polish
 * stage. Sibling of [ModelManifest]. Picks favour the 1-3B parameter range
 * called out in `docs/adr/0002-two-stage-pipeline.md` and the Slice 4 brief —
 * small enough to load briefly without unloading Whisper, large enough to
 * deliver Tier 2 quality.
 *
 * Quantizations standardise on Q4_K_M as the default — the broadest
 * size/quality compromise on mainstream Android hardware; users with more
 * RAM headroom can import higher-bit variants via the picker.
 */
object LlmModelManifest {
    private const val GEMMA_BASE = "https://huggingface.co/google/gemma-3-1b-it-qat-q4_0-gguf/resolve/main"
    private const val LFM_BASE = "https://huggingface.co/LiquidAI/LFM2-1.2B-GGUF/resolve/main"
    private const val QWEN_BASE = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main"

    val models = listOf(
        LlmModelInfo(
            name = "gemma-3-1b-it-q4",
            displayName = "Gemma 3 1B Instruct (Q4_0, ~720 MB)",
            fileName = "gemma-3-1b-it-q4_0.gguf",
            url = "$GEMMA_BASE/gemma-3-1b-it-q4_0.gguf",
            sizeBytes = 720_000_000L,
            parameterBillions = 1.0f,
            quantization = "Q4_0",
            contextLength = 32_768,
        ),
        LlmModelInfo(
            name = "lfm2-1.2b-q4",
            displayName = "LFM2 1.2B (Q4_K_M, ~770 MB)",
            fileName = "LFM2-1.2B-Q4_K_M.gguf",
            url = "$LFM_BASE/LFM2-1.2B-Q4_K_M.gguf",
            sizeBytes = 770_000_000L,
            parameterBillions = 1.2f,
            quantization = "Q4_K_M",
            contextLength = 32_768,
        ),
        LlmModelInfo(
            name = "qwen-2.5-1.5b-q4",
            displayName = "Qwen2.5 1.5B Instruct (Q4_K_M, ~990 MB)",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "$QWEN_BASE/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 990_000_000L,
            parameterBillions = 1.5f,
            quantization = "Q4_K_M",
            contextLength = 32_768,
        ),
    )

    fun getByName(name: String): LlmModelInfo? = models.find { it.name == name }
}
