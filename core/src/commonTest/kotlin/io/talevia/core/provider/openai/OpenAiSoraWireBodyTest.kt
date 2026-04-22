package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.talevia.core.platform.VideoGenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `/v1/videos` create-request wire shape. Sora rejects `seed` with
 * 400 Unknown parameter (verified live 2026-04), so seed must never be sent —
 * even if a caller tries to sneak it in via `parameters`. The field stays on
 * [VideoGenRequest] and on [io.talevia.core.platform.GenerationProvenance.seed]
 * for lockfile cache-key discipline.
 */
class OpenAiSoraWireBodyTest {

    private fun engine() = OpenAiSoraVideoGenEngine(
        httpClient = HttpClient(MockEngine { error("wire body test — no HTTP expected") }),
        apiKey = "test-key",
    )

    private fun baseRequest(
        parameters: Map<String, String> = emptyMap(),
    ) = VideoGenRequest(
        prompt = "a red panda at sunset",
        modelId = "sora-2",
        width = 1920,
        height = 1080,
        durationSeconds = 6.0,
        seed = 42L,
        parameters = parameters,
    )

    @Test fun wireBodyDoesNotIncludeSeed() {
        val wire = engine().buildWireBody(baseRequest())
        assertFalse("seed" in wire, "wire body must NOT include `seed` — OpenAI rejects it")
    }

    @Test fun wireBodyDropsSeedFromCallerParameters() {
        // Defensive: even if somebody passes a manual `seed` via parameters,
        // we still strip it so we never hit the 400 Unknown parameter error.
        val wire = engine().buildWireBody(baseRequest(parameters = mapOf("seed" to "99")))
        assertFalse("seed" in wire)
    }

    @Test fun wireBodyKeepsRequiredFields() {
        val wire = engine().buildWireBody(baseRequest())
        assertTrue("model" in wire)
        assertTrue("prompt" in wire)
        assertTrue("size" in wire)
        assertTrue("seconds" in wire)
        assertEquals("\"sora-2\"", wire["model"].toString())
        assertEquals("\"1920x1080\"", wire["size"].toString())
    }

    @Test fun wireBodyPassesThroughNonSeedParameters() {
        val wire = engine().buildWireBody(baseRequest(parameters = mapOf("quality" to "high")))
        assertTrue("quality" in wire)
        assertFalse("seed" in wire)
    }
}
