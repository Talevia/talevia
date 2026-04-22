package io.talevia.core.cost

import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the shape of [AigcPricing]'s three-state output — NOT the
 * accuracy of any given price point. Provider prices drift; this suite
 * guards: (a) known provider/model/call-shape returns a positive Long, (b)
 * anything unknown returns null (not 0 or a guess), (c) the extractor is
 * tolerant of missing / wrong-type fields.
 */
class AigcPricingTest {

    private fun provenance(
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
    ): GenerationProvenance = GenerationProvenance(
        providerId = providerId,
        modelId = modelId,
        modelVersion = null,
        seed = 0,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 0,
    )

    @Test fun knownImageSquareCharges4Cents() {
        val input = buildJsonObject { put("width", 1024); put("height", 1024) }
        val cents = AigcPricing.estimateCents("generate_image", provenance(), input)
        assertEquals(4L, cents)
    }

    @Test fun knownImageNonSquareCharges6Cents() {
        val input = buildJsonObject { put("width", 1536); put("height", 1024) }
        val cents = AigcPricing.estimateCents("generate_image", provenance(), input)
        assertEquals(6L, cents)
    }

    @Test fun unknownImageModelReturnsNullNotGuess() {
        val input = buildJsonObject { put("width", 1024); put("height", 1024) }
        val cents = AigcPricing.estimateCents(
            "generate_image",
            provenance(modelId = "some-future-model-v7"),
            input,
        )
        assertNull(cents, "unknown models must not fabricate a price — null is the signal")
    }

    @Test fun ttsCostsByCharacterLength() {
        val longText = "a".repeat(10_000) // 10K chars × $0.000015 = $0.15 = 15 cents
        val input = buildJsonObject { put("text", longText) }
        val cents = AigcPricing.estimateCents(
            "synthesize_speech",
            provenance(modelId = "tts-1"),
            input,
        )
        assertEquals(15L, cents)
    }

    @Test fun ttsHdDoublesTheRate() {
        val text = "a".repeat(10_000)
        val input = buildJsonObject { put("text", text) }
        val cents = AigcPricing.estimateCents(
            "synthesize_speech",
            provenance(modelId = "tts-1-hd"),
            input,
        )
        assertEquals(30L, cents)
    }

    @Test fun ttsReturnsNullWithoutText() {
        val cents = AigcPricing.estimateCents(
            "synthesize_speech",
            provenance(modelId = "tts-1"),
            JsonObject(emptyMap()),
        )
        assertNull(cents)
    }

    @Test fun musicCostScalesWithDuration() {
        val input = buildJsonObject { put("durationSeconds", 15) }
        val cents = AigcPricing.estimateCents(
            "generate_music",
            provenance(providerId = "replicate", modelId = "meta/musicgen:some-ver"),
            input,
        )
        assertEquals(30L, cents) // 15s × 2¢/s
    }

    @Test fun upscaleReturnsFlatEstimate() {
        val cents = AigcPricing.estimateCents(
            "upscale_asset",
            provenance(providerId = "replicate", modelId = "nightmareai/real-esrgan:abcdef"),
            JsonObject(emptyMap()),
        )
        assertEquals(5L, cents)
    }

    @Test fun unknownToolIdReturnsNull() {
        val cents = AigcPricing.estimateCents(
            "not_a_known_tool",
            provenance(),
            JsonObject(emptyMap()),
        )
        assertNull(cents)
    }

    @Test fun tolerantToWrongTypeField() {
        val input = buildJsonObject {
            // width should be int, send a string — extractor returns null rather than crash
            put("width", JsonPrimitive("nope"))
            put("height", 1024)
        }
        val cents = AigcPricing.estimateCents("generate_image", provenance(), input)
        assertNull(cents)
    }

    @Test fun videoCostScalesWithDuration() {
        val input = buildJsonObject { put("durationSeconds", 10) }
        val cents = AigcPricing.estimateCents(
            "generate_video",
            provenance(providerId = "openai", modelId = "sora"),
            input,
        )
        assertEquals(300L, cents) // 10s × 30¢/s
        assertTrue((cents ?: 0) > 0)
    }
}
