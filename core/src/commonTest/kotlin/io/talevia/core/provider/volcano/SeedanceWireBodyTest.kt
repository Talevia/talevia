package io.talevia.core.provider.volcano

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.talevia.core.platform.VideoGenRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the ARK `/api/v3/contents/generations/tasks` create-request wire shape
 * for Seedance. Mirrors `OpenAiSoraWireBodyTest`'s posture — once the schema
 * is right the live HTTP path is mostly plumbing, but a wrong wire shape
 * shows up as a 400 only in prod, so the schema invariants are unit-pinned.
 *
 * Invariants:
 *  1. Seed is never on the wire (ARK rejects it on Seedance as of 2026-04;
 *     same defensive stance as Sora). Even a caller-supplied
 *     `parameters["seed"]` is filtered.
 *  2. `content` is the structured `[{type:"text", text:"..."}]` array, NOT a
 *     bare prompt string — ARK rejects the latter.
 *  3. Resolution / ratio are derived from `(width, height)` to ARK's coarse
 *     enums; caller can override either via `parameters`.
 *  4. Duration rounds up (5.5s → 6) and clamps to ≥ 1.
 *  5. Watermark defaults off so agent-driven exports don't leak it into the
 *     composite output; caller can override.
 */
class SeedanceWireBodyTest {

    private fun engine() = SeedanceVideoGenEngine(
        httpClient = HttpClient(MockEngine { error("wire body test — no HTTP expected") }),
        apiKey = "test-key",
    )

    private fun baseRequest(
        width: Int = 1280,
        height: Int = 720,
        durationSeconds: Double = 5.0,
        parameters: Map<String, String> = emptyMap(),
    ) = VideoGenRequest(
        prompt = "a red panda at sunset",
        modelId = "doubao-seedance-2-0-260128",
        width = width,
        height = height,
        durationSeconds = durationSeconds,
        seed = 42L,
        parameters = parameters,
    )

    @Test fun wireBodyDoesNotIncludeSeed() {
        val wire = engine().buildWireBody(baseRequest())
        assertFalse("seed" in wire, "wire body must NOT include `seed` — ARK rejects it on Seedance")
    }

    @Test fun wireBodyDropsSeedFromCallerParameters() {
        val wire = engine().buildWireBody(baseRequest(parameters = mapOf("seed" to "99")))
        assertFalse("seed" in wire, "caller-supplied `seed` must be filtered, not smuggled through")
    }

    @Test fun wireBodyKeepsRequiredFields() {
        val wire = engine().buildWireBody(baseRequest())
        assertTrue("model" in wire)
        assertTrue("content" in wire)
        assertTrue("resolution" in wire)
        assertTrue("ratio" in wire)
        assertTrue("duration" in wire)
        assertTrue("watermark" in wire)
        assertEquals("doubao-seedance-2-0-260128", wire["model"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyContentIsStructuredTextArray() {
        val wire = engine().buildWireBody(baseRequest())
        val content = wire["content"]!!.jsonArray
        assertEquals(1, content.size, "single text part for text-to-video")
        val part = content[0].jsonObject
        assertEquals("text", part["type"]!!.jsonPrimitive.content)
        assertEquals("a red panda at sunset", part["text"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyDerivesLandscape720pAndSixteenNine() {
        val wire = engine().buildWireBody(baseRequest(width = 1280, height = 720))
        assertEquals("720p", wire["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", wire["ratio"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyDerivesPortrait1080pAndNineSixteen() {
        val wire = engine().buildWireBody(baseRequest(width = 1080, height = 1920))
        assertEquals("1080p", wire["resolution"]!!.jsonPrimitive.content)
        assertEquals("9:16", wire["ratio"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyDerivesSquare1to1() {
        val wire = engine().buildWireBody(baseRequest(width = 720, height = 720))
        assertEquals("720p", wire["resolution"]!!.jsonPrimitive.content)
        assertEquals("1:1", wire["ratio"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyDerivesCinematic2K21To9() {
        // 21:9 short side > 1080 → 2K bucket; ratio resolves to 21:9.
        val wire = engine().buildWireBody(baseRequest(width = 3360, height = 1440))
        assertEquals("2K", wire["resolution"]!!.jsonPrimitive.content)
        assertEquals("21:9", wire["ratio"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyClampsBelow480pTo480p() {
        // Anything sub-480 short side rounds *up* to 480p — the smallest
        // tier the ARK enum offers. Avoids a 400 on tiny test inputs.
        val wire = engine().buildWireBody(baseRequest(width = 256, height = 144))
        assertEquals("480p", wire["resolution"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyRoundsDurationUp() {
        // 5.5 → 6 (don't silently truncate); 0.4 → 1 (clamp to ARK min).
        val wire1 = engine().buildWireBody(baseRequest(durationSeconds = 5.5))
        assertEquals(6, wire1["duration"]!!.jsonPrimitive.content.toInt())
        val wire2 = engine().buildWireBody(baseRequest(durationSeconds = 0.4))
        assertEquals(1, wire2["duration"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun wireBodyWatermarkOffByDefault() {
        val wire = engine().buildWireBody(baseRequest())
        assertEquals("false", wire["watermark"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyCallerCanOverrideRatioAndWatermarkViaParameters() {
        val wire = engine().buildWireBody(
            baseRequest(
                width = 1280,
                height = 720,
                parameters = mapOf("ratio" to "21:9", "watermark" to "true"),
            ),
        )
        assertEquals("21:9", wire["ratio"]!!.jsonPrimitive.content)
        assertEquals("true", wire["watermark"]!!.jsonPrimitive.content)
    }

    @Test fun wireBodyPassesThroughNonReservedParameters() {
        val wire = engine().buildWireBody(
            baseRequest(parameters = mapOf("generate_audio" to "true")),
        )
        assertEquals("true", wire["generate_audio"]!!.jsonPrimitive.content)
        assertFalse("seed" in wire)
    }

    @Test fun deriveResolutionShortSidePivots() {
        // Pivot is min(w, h) so portrait + landscape with same short side
        // resolve identically — matching ARK's documented behaviour.
        assertEquals("720p", SeedanceVideoGenEngine.deriveResolution(1280, 720))
        assertEquals("720p", SeedanceVideoGenEngine.deriveResolution(720, 1280))
        assertEquals("1080p", SeedanceVideoGenEngine.deriveResolution(1920, 1080))
        assertEquals("480p", SeedanceVideoGenEngine.deriveResolution(640, 360))
        assertEquals("2K", SeedanceVideoGenEngine.deriveResolution(3840, 2160))
    }

    @Test fun deriveRatioFallsBackToSixteenNineOnZeroHeight() {
        assertEquals("16:9", SeedanceVideoGenEngine.deriveRatio(1280, 0))
        assertEquals("16:9", SeedanceVideoGenEngine.deriveRatio(0, 720))
    }
}
