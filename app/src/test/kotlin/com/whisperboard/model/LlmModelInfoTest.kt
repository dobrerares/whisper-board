package com.whisperboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + manifest tests for the LLM model metadata layer. These cover
 * the same shape as Whisper's [ModelInfo] tests would: persistence
 * round-trip, custom-model identification, and manifest lookup. Native /
 * file-system / DataStore behaviours are exercised on-device, not here.
 */
class LlmModelInfoTest {

    private val sample = LlmModelInfo(
        name = "test-1b-q4",
        displayName = "Test 1B (Q4_K_M)",
        fileName = "test-1b.gguf",
        url = "https://example.invalid/test-1b.gguf",
        sizeBytes = 700_000_000L,
        sha256 = "deadbeef",
        parameterBillions = 1.0f,
        quantization = "Q4_K_M",
        contextLength = 4096,
        isCustom = false,
    )

    @Test
    fun `JSON round-trip preserves all LLM-specific metadata`() {
        val json = sample.toJson().toString()
        val restored = LlmModelInfo.fromJson(org.json.JSONObject(json))

        assertEquals(sample.name, restored.name)
        assertEquals(sample.displayName, restored.displayName)
        assertEquals(sample.fileName, restored.fileName)
        assertEquals(sample.url, restored.url)
        assertEquals(sample.sizeBytes, restored.sizeBytes)
        assertEquals(sample.sha256, restored.sha256)
        assertEquals(sample.parameterBillions, restored.parameterBillions, 0.001f)
        assertEquals(sample.quantization, restored.quantization)
        assertEquals(sample.contextLength, restored.contextLength)
    }

    @Test
    fun `fromJson always marks restored entries as custom`() {
        // The JSON list is only ever used to persist user-imported models.
        // Stock-manifest entries are sourced from LlmModelManifest, so any
        // entry that survives a JSON round-trip is by definition custom.
        val json = sample.toJson().toString()
        val restored = LlmModelInfo.fromJson(org.json.JSONObject(json))
        assertTrue(restored.isCustom)
    }

    @Test
    fun `list round-trip preserves order`() {
        val a = sample.copy(name = "a")
        val b = sample.copy(name = "b")
        val c = sample.copy(name = "c")
        val json = LlmModelInfo.listToJson(listOf(a, b, c))
        val restored = LlmModelInfo.listFromJson(json)
        assertEquals(listOf("a", "b", "c"), restored.map { it.name })
    }

    @Test
    fun `empty JSON list parses to empty list`() {
        assertEquals(emptyList<LlmModelInfo>(), LlmModelInfo.listFromJson(""))
    }

    @Test
    fun `manifest exposes 1-to-3B-range stock entries`() {
        // Per ADR-0002 + Slice 4 brief: stock catalogue covers the 1-3B range.
        val sizes = LlmModelManifest.models.map { it.parameterBillions }
        assertTrue(
            "all stock entries must be in the 1-3B range; got $sizes",
            sizes.all { it in 1.0f..3.0f },
        )
    }

    @Test
    fun `manifest lookup is by canonical name`() {
        val first = LlmModelManifest.models.first()
        assertEquals(first, LlmModelManifest.getByName(first.name))
        assertNull(LlmModelManifest.getByName("not-a-real-model"))
    }

    @Test
    fun `manifest entries have non-blank URLs and file names`() {
        for (m in LlmModelManifest.models) {
            assertTrue("url for ${m.name} should be non-blank", m.url.isNotBlank())
            assertTrue("fileName for ${m.name} should be non-blank", m.fileName.isNotBlank())
            assertNotNull(m.quantization)
            assertTrue("contextLength for ${m.name} should be positive", m.contextLength > 0)
        }
    }
}
