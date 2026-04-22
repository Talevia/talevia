package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.talevia.core.platform.ImageGenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `/v1/images/generations` create-request wire shape. The current
 * OpenAI image models (`gpt-image-1`, `dall-e-3`) reject `seed` with
 * 400 `Unknown parameter: 'seed'` (verified live 2026-04 alongside the Sora
 * sibling). Seed stays in [io.talevia.core.platform.GenerationProvenance.seed]
 * for lockfile cache-key discipline but must never reach the wire.
 */
class OpenAiImageGenWireBodyTest {

    private fun engine() = OpenAiImageGenEngine(
        httpClient = HttpClient(MockEngine { error("wire body test — no HTTP expected") }),
        apiKey = "test-key",
    )

    private fun baseRequest(
        parameters: Map<String, String> = emptyMap(),
    ) = ImageGenRequest(
        prompt = "a red panda at sunset",
        modelId = "gpt-image-1",
        width = 1024,
        height = 1024,
        seed = 42L,
        parameters = parameters,
    )

    @Test fun wireBodyDoesNotIncludeSeed() {
        val wire = engine().buildWireBody(baseRequest())
        assertFalse("seed" in wire, "wire body must NOT include `seed` — OpenAI rejects it")
    }

    @Test fun wireBodyDropsSeedFromCallerParameters() {
        val wire = engine().buildWireBody(baseRequest(parameters = mapOf("seed" to "99")))
        assertFalse("seed" in wire)
    }

    @Test fun wireBodyKeepsRequiredFields() {
        val wire = engine().buildWireBody(baseRequest())
        assertTrue("model" in wire)
        assertTrue("prompt" in wire)
        assertTrue("size" in wire)
        assertTrue("n" in wire)
        assertTrue("response_format" in wire)
        assertEquals("\"1024x1024\"", wire["size"].toString())
    }

    @Test fun wireBodyPassesThroughNonSeedParameters() {
        val wire = engine().buildWireBody(baseRequest(parameters = mapOf("quality" to "hd")))
        assertTrue("quality" in wire)
        assertFalse("seed" in wire)
    }
}
