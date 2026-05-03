package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.talevia.core.bus.BusEvent
import io.talevia.core.platform.TtsRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [OpenAiTtsEngine] —
 * `core/src/commonMain/kotlin/io/talevia/core/provider/openai/OpenAiTtsEngine.kt`.
 * Cycle 248 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-247.
 *
 * `OpenAiTtsEngine` is the provider-native HTTP shim for OpenAI's
 * Speech endpoint (`POST /v1/audio/speech`). Drift in any wire-
 * format detail breaks every speech synthesis call:
 *
 *   - URL path drift → 404 → "synthesize_speech failed" with no
 *     useful diagnostic.
 *   - Body field rename (e.g. `input` → `text`) → OpenAI returns
 *     400 from the server; user sees a generic failure.
 *   - Bearer auth omitted → 401.
 *   - Warmup callback ordering wrong → CLI / Desktop "warming up
 *     openai…" indicator never settles.
 *
 * Pins three correctness contracts:
 *
 *  1. **HTTP request shape**: POST to `$baseUrl/v1/audio/speech`
 *     with Content-Type `application/json` + Bearer auth header
 *     + JSON body `{model, input, voice, response_format, speed}`.
 *     Drift in URL / method / headers / body field names would
 *     break the wire integration silently from the test side.
 *
 *  2. **Warmup callback ordering**: the supplied `onWarmup`
 *     fires `Starting` BEFORE the HTTP request, `Ready` AFTER a
 *     successful response. On a non-OK response, `Ready` MUST
 *     NOT fire (drift would let downstream UIs paint "ready"
 *     even on 401 / 5xx, leaking the failure surface).
 *
 *  3. **Result population on success**: `TtsResult.audio.audioBytes`
 *     equals the raw response bytes; `audio.format` echoes
 *     `request.format`; `provenance.providerId == "openai"`;
 *     `provenance.modelVersion == null` (OpenAI doesn't surface
 *     this); `provenance.seed == 0L`; `provenance.parameters`
 *     equals the wire body (so downstream lockfile pinning sees
 *     the exact request); `provenance.createdAtEpochMs` comes
 *     from the injected clock.
 *
 * Plus error-path pins:
 *   - Non-OK status throws with status code + response body.
 *   - 200 with empty audio body throws `"returned empty audio body"`
 *     (drift to "return empty TtsResult" would silently produce
 *     a zero-length audio asset).
 *   - Extra `parameters` map fields merge into the JSON body
 *     (the kdoc-documented extension point).
 *
 * Plus class-level invariant:
 *   - `providerId == "openai"` (drift would silently de-link
 *     this engine from the OpenAI provider warmup / billing path).
 */
class OpenAiTtsEngineTest {

    private val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00) // "ID3" v4 header bytes

    private fun audioHeaders() = headersOf(HttpHeaders.ContentType, "audio/mpeg")
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun mockHttp(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val engine = MockEngine { req -> handler(req) }
        return HttpClient(engine)
    }

    private fun fixedClock(epochMs: Long) = object : kotlinx.datetime.Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    private val sampleRequest = TtsRequest(
        text = "hello world",
        modelId = "tts-1",
        voice = "alloy",
        format = "mp3",
        speed = 1.0,
        parameters = emptyMap(),
    )

    // ── 0. Class-level invariant ────────────────────────────

    @Test fun providerIdIsOpenAi() {
        // Marquee provider-id pin: drift would silently de-link
        // this engine from the OpenAI provider warmup / billing
        // path. ProviderRegistry-keyed lookups (cost, rate-limit,
        // engine-readiness) all branch on `providerId` strings.
        val engine = OpenAiTtsEngine(httpClient = HttpClient(MockEngine { error("not used") }), apiKey = "test")
        assertEquals("openai", engine.providerId)
    }

    // ── 1. HTTP request shape ───────────────────────────────

    @Test fun synthesizePostsToCanonicalSpeechEndpointWithBearerAuth() = runTest {
        // Marquee URL + auth pin: drift in path or to a different
        // auth scheme silently breaks the wire integration.
        var capturedUrl: String? = null
        var capturedMethod: String? = null
        var capturedAuth: String? = null
        var capturedContentType: String? = null
        val http = mockHttp { req ->
            capturedUrl = req.url.toString()
            capturedMethod = req.method.value
            capturedAuth = req.headers[HttpHeaders.Authorization]
            capturedContentType = req.headers[HttpHeaders.ContentType]
                ?: req.body.contentType?.toString()
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "secret-key")

        engine.synthesize(sampleRequest)

        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            capturedUrl,
            "synthesize MUST POST to /v1/audio/speech under the configured baseUrl",
        )
        assertEquals("POST", capturedMethod, "MUST use POST")
        assertEquals("Bearer secret-key", capturedAuth, "MUST set Bearer auth from apiKey")
        assertTrue(
            capturedContentType?.startsWith("application/json") == true,
            "MUST send Content-Type application/json; got: $capturedContentType",
        )
    }

    @Test fun synthesizeRespectsCustomBaseUrl() = runTest {
        // Pin: the constructor's baseUrl arg is the override path
        // for non-prod / proxy / Azure-OpenAI endpoints. Drift to
        // ignore it would silently route to api.openai.com even
        // when caller wired a corporate proxy.
        var capturedUrl: String? = null
        val http = mockHttp { req ->
            capturedUrl = req.url.toString()
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(
            httpClient = http,
            apiKey = "k",
            baseUrl = "https://my-corp-proxy.example.com",
        )

        engine.synthesize(sampleRequest)

        assertEquals(
            "https://my-corp-proxy.example.com/v1/audio/speech",
            capturedUrl,
            "baseUrl override MUST be respected",
        )
    }

    @Test fun bodyContainsCanonicalFiveFields() = runTest {
        // Marquee wire-body pin: per the kdoc, the body shape is
        // `{model, input, voice, response_format, speed}`. Drift
        // in field names (e.g. `text` instead of `input`, `format`
        // instead of `response_format`) would silently break the
        // OpenAI server-side parser.
        var bodyText: String? = null
        val http = mockHttp { req ->
            bodyText = (req.body as? io.ktor.http.content.TextContent)?.text
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k")

        engine.synthesize(
            TtsRequest(
                text = "hello",
                modelId = "tts-1-hd",
                voice = "nova",
                format = "wav",
                speed = 0.85,
                parameters = emptyMap(),
            ),
        )

        val body = Json.parseToJsonElement(bodyText!!).jsonObject
        assertEquals("tts-1-hd", body["model"]?.jsonPrimitive?.content, "body.model")
        assertEquals("hello", body["input"]?.jsonPrimitive?.content, "body.input MUST carry the text (NOT 'text' key)")
        assertEquals("nova", body["voice"]?.jsonPrimitive?.content, "body.voice")
        assertEquals(
            "wav",
            body["response_format"]?.jsonPrimitive?.content,
            "body.response_format MUST be the OpenAI-canonical key (NOT 'format')",
        )
        assertEquals(0.85, body["speed"]?.jsonPrimitive?.content?.toDouble(), "body.speed")
    }

    @Test fun extraParametersAreMergedIntoBody() = runTest {
        // Pin: the kdoc-documented extension point — `request.parameters`
        // is a map that gets merged into the body. Drift to "ignore
        // parameters" would silently lose forward-compat fields the
        // caller passes through.
        var bodyText: String? = null
        val http = mockHttp { req ->
            bodyText = (req.body as? io.ktor.http.content.TextContent)?.text
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k")

        engine.synthesize(
            sampleRequest.copy(
                parameters = mapOf(
                    "experimental_field" to "true",
                    "voice_modulation" to "verbose",
                ),
            ),
        )

        val body = Json.parseToJsonElement(bodyText!!).jsonObject
        assertEquals("true", body["experimental_field"]?.jsonPrimitive?.content)
        assertEquals("verbose", body["voice_modulation"]?.jsonPrimitive?.content)
        // Original 5 fields still present.
        assertEquals("tts-1", body["model"]?.jsonPrimitive?.content)
    }

    // ── 2. Warmup callback ordering ─────────────────────────

    @Test fun warmupFiresStartingThenReadyOnSuccess() = runTest {
        // Marquee warmup-ordering pin: Starting fires BEFORE the
        // HTTP request, Ready AFTER a successful response. The
        // CLI / Desktop "warming up openai…" indicator depends on
        // this ordering.
        val phasesObserved = mutableListOf<BusEvent.ProviderWarmup.Phase>()
        var phasesBeforeHttp: List<BusEvent.ProviderWarmup.Phase> = emptyList()
        val http = mockHttp { _ ->
            // Capture what fired BEFORE the response is delivered.
            phasesBeforeHttp = phasesObserved.toList()
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k")

        engine.synthesize(sampleRequest, onWarmup = { phase -> phasesObserved += phase })

        assertEquals(
            listOf(BusEvent.ProviderWarmup.Phase.Starting),
            phasesBeforeHttp,
            "Starting MUST fire BEFORE the HTTP request lands",
        )
        assertEquals(
            listOf(BusEvent.ProviderWarmup.Phase.Starting, BusEvent.ProviderWarmup.Phase.Ready),
            phasesObserved,
            "AFTER success: full sequence Starting → Ready",
        )
    }

    @Test fun warmupReadyDoesNotFireOnNonOkStatus() = runTest {
        // Marquee error-path warmup pin: per the kdoc, on a
        // non-OK response the function `error()`s BEFORE
        // publishing Ready. Drift to "always fire Ready" would
        // let downstream UIs paint "ready" on 401 / 5xx,
        // masking the failure surface.
        val phasesObserved = mutableListOf<BusEvent.ProviderWarmup.Phase>()
        val http = mockHttp { _ ->
            respond(
                content = """{"error":"invalid api key"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "wrong-key")

        assertFailsWith<IllegalStateException> {
            engine.synthesize(sampleRequest, onWarmup = { phase -> phasesObserved += phase })
        }

        assertEquals(
            listOf(BusEvent.ProviderWarmup.Phase.Starting),
            phasesObserved,
            "On non-OK, ONLY Starting MUST fire (Ready is suppressed before the error)",
        )
    }

    // ── 3. Result population on success ─────────────────────

    @Test fun successReturnsAudioBytesAndProvenance() = runTest {
        // Marquee result-shape pin: audio.audioBytes equals the
        // raw response bytes; format echoes request.format;
        // provenance carries provider/model + null modelVersion +
        // 0L seed + clock-stamped time + parameters mirroring
        // the wire body.
        val http = mockHttp { _ ->
            respond(
                content = ByteReadChannel(audioBytes),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val clock = fixedClock(epochMs = 1_700_000_000_000L)
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k", clock = clock)

        val result = engine.synthesize(sampleRequest)

        // Audio + format.
        assertTrue(
            result.audio.audioBytes.contentEquals(audioBytes),
            "audio.audioBytes MUST equal the raw response bytes",
        )
        assertEquals("mp3", result.audio.format, "audio.format MUST echo request.format")

        // Provenance.
        assertEquals("openai", result.provenance.providerId)
        assertEquals("tts-1", result.provenance.modelId, "modelId echoes request")
        assertNull(
            result.provenance.modelVersion,
            "OpenAI Speech doesn't surface modelVersion — MUST be null per kdoc",
        )
        assertEquals(0L, result.provenance.seed, "TTS has no seed concept; MUST be 0L")
        assertEquals(
            1_700_000_000_000L,
            result.provenance.createdAtEpochMs,
            "createdAtEpochMs MUST come from the injected clock",
        )
        // Parameters mirror the wire body — load-bearing for
        // lockfile cache pinning since the lockfile keys on the
        // inputs that produced the artifact.
        val params = result.provenance.parameters
        assertEquals(
            "tts-1",
            params["model"]?.jsonPrimitive?.content,
            "provenance.parameters MUST mirror the wire body",
        )
        assertEquals("hello world", params["input"]?.jsonPrimitive?.content)
        assertEquals("alloy", params["voice"]?.jsonPrimitive?.content)
    }

    // ── 4. Error paths ──────────────────────────────────────

    @Test fun nonOkStatusThrowsWithStatusCodeAndBody() = runTest {
        // Marquee error-message pin: drift to a generic
        // "request failed" without status code or body would
        // strip the diagnosability the agent / operator needs.
        val http = mockHttp { _ ->
            respond(
                content = """{"error":{"message":"rate limited"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k")

        val ex = assertFailsWith<IllegalStateException> {
            engine.synthesize(sampleRequest)
        }
        val msg = ex.message ?: ""
        assertTrue(
            "OpenAI TTS request failed" in msg,
            "error MUST cite 'OpenAI TTS request failed'; got: $msg",
        )
        assertTrue(
            "429 Too Many Requests" in msg || "429" in msg,
            "error MUST cite the status code; got: $msg",
        )
        assertTrue(
            "rate limited" in msg,
            "error MUST cite the response body so caller sees the underlying reason; got: $msg",
        )
    }

    @Test fun emptyAudioBodyThrows() = runTest {
        // Marquee empty-body pin: drift to "return empty
        // TtsResult" would silently produce a zero-length audio
        // asset that lands in the timeline, surfacing only as a
        // user-visible silent clip. The error here is the only
        // line of defense against that.
        val http = mockHttp { _ ->
            respond(
                content = ByteReadChannel(byteArrayOf()),
                status = HttpStatusCode.OK,
                headers = audioHeaders(),
            )
        }
        val engine = OpenAiTtsEngine(httpClient = http, apiKey = "k")

        val ex = assertFailsWith<IllegalStateException> {
            engine.synthesize(sampleRequest)
        }
        assertTrue(
            "returned empty audio body" in (ex.message ?: ""),
            "empty audio bytes MUST throw with canonical message; got: ${ex.message}",
        )
    }
}
