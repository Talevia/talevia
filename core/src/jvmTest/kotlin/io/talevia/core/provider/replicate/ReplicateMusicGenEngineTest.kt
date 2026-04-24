package io.talevia.core.provider.replicate

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
import io.talevia.core.platform.MusicGenRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplicateMusicGenEngineTest {

    private val audioBody = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun audioHeaders() = headersOf(HttpHeaders.ContentType, "audio/mpeg")

    /** Wire a MockEngine that returns canned bodies per request, tracking call count. */
    private fun mockHttp(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val engine = MockEngine { req -> handler(req) }
        return HttpClient(engine)
    }

    @Test fun happyPath_submitPollDownload() = runTest {
        var calls = 0
        val http = mockHttp { req ->
            calls += 1
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> {
                    respond(
                        content = """
                            {"id":"pred-1","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/pred-1"}}
                        """.trimIndent(),
                        status = HttpStatusCode.Created,
                        headers = jsonHeaders(),
                    )
                }
                req.url.toString().endsWith("/predictions/pred-1") -> {
                    // First poll: still processing. Second poll: succeeded.
                    val body = if (calls <= 2) {
                        """{"id":"pred-1","status":"processing"}"""
                    } else {
                        """{"id":"pred-1","status":"succeeded","output":"https://cdn.replicate.delivery/pred-1.mp3"}"""
                    }
                    respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders())
                }
                req.url.toString().endsWith("pred-1.mp3") -> {
                    respond(
                        content = ByteReadChannel(audioBody),
                        status = HttpStatusCode.OK,
                        headers = audioHeaders(),
                    )
                }
                else -> error("unexpected request: ${req.method} ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)

        val result = engine.generate(
            MusicGenRequest(
                prompt = "warm acoustic",
                modelId = "musicgen-melody",
                seed = 42L,
                durationSeconds = 10.0,
                format = "mp3",
            ),
        )

        assertEquals(audioBody.toList(), result.music.audioBytes.toList())
        assertEquals("mp3", result.music.format)
        assertEquals(10.0, result.music.durationSeconds)
        assertEquals("replicate", result.provenance.providerId)
        assertEquals("meta/musicgen", result.provenance.modelId)
        assertEquals(42L, result.provenance.seed)
    }

    @Test fun arrayOutputShapeIsHandled() = runTest {
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"p2","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/p2"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/p2") -> respond(
                    content = """{"id":"p2","status":"succeeded","output":["https://cdn.replicate.delivery/p2.mp3"]}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("p2.mp3") -> respond(
                    content = ByteReadChannel(audioBody),
                    status = HttpStatusCode.OK,
                    headers = audioHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val result = engine.generate(
            MusicGenRequest(prompt = "x", modelId = "m", seed = 1L, durationSeconds = 5.0),
        )
        assertEquals(audioBody.toList(), result.music.audioBytes.toList())
    }

    @Test fun failedStatusRaises() = runTest {
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"p3","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/p3"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/p3") -> respond(
                    content = """{"id":"p3","status":"failed","error":"model timeout"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val ex = assertFailsWith<IllegalStateException> {
            engine.generate(MusicGenRequest(prompt = "x", modelId = "m", seed = 1L, durationSeconds = 5.0))
        }
        assertTrue(ex.message!!.contains("status=failed"), ex.message)
    }

    @Test fun warmupPhasesFireInOrder() = runTest {
        // Verifies the contract behind BusEvent.ProviderWarmup emission:
        // Starting is published before the initial POST and Ready is
        // published on the first successful poll (not on later polls or on
        // terminal success). Assertions key on ordering and cardinality so
        // subscribers can rely on `Starting ≺ Ready` and "at-most-one Ready
        // per generate() call".
        var calls = 0
        val http = mockHttp { req ->
            calls += 1
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"pw","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/pw"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/pw") -> {
                    // Two poll responses: first still processing, second terminal.
                    // Ready should fire on the first (not the second).
                    val body = if (calls <= 2) {
                        """{"id":"pw","status":"processing"}"""
                    } else {
                        """{"id":"pw","status":"succeeded","output":"https://cdn.replicate.delivery/pw.mp3"}"""
                    }
                    respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders())
                }
                req.url.toString().endsWith("pw.mp3") -> respond(
                    content = ByteReadChannel(audioBody),
                    status = HttpStatusCode.OK,
                    headers = audioHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val phases = mutableListOf<BusEvent.ProviderWarmup.Phase>()
        engine.generate(
            MusicGenRequest(prompt = "x", modelId = "m", seed = 1L, durationSeconds = 5.0),
            onWarmup = { phases += it },
        )
        assertEquals(
            listOf(BusEvent.ProviderWarmup.Phase.Starting, BusEvent.ProviderWarmup.Phase.Ready),
            phases,
        )
    }

    @Test fun warmupStartingFiresEvenIfCreateFails() = runTest {
        // Starting is emitted before the POST, so a provider that 500s on
        // create still produces a Starting (no Ready) — UI shows "warming
        // up…" and then the outer error surface takes over.
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"error":"boom"}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = jsonHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val phases = mutableListOf<BusEvent.ProviderWarmup.Phase>()
        assertFailsWith<IllegalStateException> {
            engine.generate(
                MusicGenRequest(prompt = "x", modelId = "m", seed = 1L, durationSeconds = 5.0),
                onWarmup = { phases += it },
            )
        }
        assertEquals(listOf(BusEvent.ProviderWarmup.Phase.Starting), phases)
    }

    @Test fun durationIsCeilInSeconds() = runTest {
        var submittedDuration: Int? = null
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> {
                    val body = String((req.body as io.ktor.http.content.TextContent).text.toByteArray())
                    // Extract duration from JSON body: look for "duration":<N>
                    val match = Regex("\"duration\"\\s*:\\s*(\\d+)").find(body)
                    submittedDuration = match?.groupValues?.get(1)?.toInt()
                    respond(
                        content = """{"id":"p4","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/p4"}}""",
                        status = HttpStatusCode.Created,
                        headers = jsonHeaders(),
                    )
                }
                req.url.toString().endsWith("/predictions/p4") -> respond(
                    content = """{"id":"p4","status":"succeeded","output":"https://cdn.replicate.delivery/p4.mp3"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("p4.mp3") -> respond(
                    content = ByteReadChannel(audioBody),
                    status = HttpStatusCode.OK,
                    headers = audioHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateMusicGenEngine(http, apiKey = "test", pollIntervalMs = 0L)
        engine.generate(
            MusicGenRequest(prompt = "x", modelId = "m", seed = 1L, durationSeconds = 10.3),
        )
        // 10.3 → ceil → 11
        assertEquals(11, submittedDuration)
    }
}
