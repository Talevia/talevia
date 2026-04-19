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
import io.talevia.core.platform.UpscaleRequest
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplicateUpscaleEngineTest {

    private val srcBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    private val upscaledBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun pngHeaders() = headersOf(HttpHeaders.ContentType, "image/png")

    private fun mockHttp(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient {
        val engine = MockEngine { req -> handler(req) }
        return HttpClient(engine)
    }

    private fun writeSource(): File {
        val tmp = Files.createTempFile("upscale-src-", ".png").toFile()
        tmp.writeBytes(srcBytes)
        tmp.deleteOnExit()
        return tmp
    }

    @Test fun submitPollDownloadEmitsUpscaledBytes() = runTest {
        val src = writeSource()
        var calls = 0
        val http = mockHttp { req ->
            calls += 1
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"u-1","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/u-1"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/u-1") -> {
                    val body = if (calls <= 2) {
                        """{"id":"u-1","status":"processing"}"""
                    } else {
                        """{"id":"u-1","status":"succeeded","output":"https://cdn.replicate.delivery/u-1.png"}"""
                    }
                    respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders())
                }
                req.url.toString().endsWith("u-1.png") -> respond(
                    content = ByteReadChannel(upscaledBytes),
                    status = HttpStatusCode.OK,
                    headers = pngHeaders(),
                )
                else -> error("unexpected request: ${req.method} ${req.url}")
            }
        }
        val engine = ReplicateUpscaleEngine(http, apiKey = "test", pollIntervalMs = 0L)

        val result = engine.upscale(
            UpscaleRequest(
                imagePath = src.absolutePath,
                modelId = "real-esrgan-4x",
                scale = 4,
                seed = 42L,
                format = "png",
            ),
        )
        assertEquals(upscaledBytes.toList(), result.image.imageBytes.toList())
        assertEquals("png", result.image.format)
        assertEquals("replicate", result.provenance.providerId)
        assertEquals("nightmareai/real-esrgan", result.provenance.modelId)
        assertEquals(42L, result.provenance.seed)
    }

    @Test fun arrayOutputShapeIsHandled() = runTest {
        val src = writeSource()
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"u-2","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/u-2"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/u-2") -> respond(
                    content = """{"id":"u-2","status":"succeeded","output":["https://cdn.replicate.delivery/u-2.png"]}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("u-2.png") -> respond(
                    content = ByteReadChannel(upscaledBytes),
                    status = HttpStatusCode.OK,
                    headers = pngHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateUpscaleEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val result = engine.upscale(
            UpscaleRequest(imagePath = src.absolutePath, modelId = "m", scale = 2, seed = 1L),
        )
        assertEquals(upscaledBytes.toList(), result.image.imageBytes.toList())
    }

    @Test fun failedStatusRaises() = runTest {
        val src = writeSource()
        val http = mockHttp { req ->
            when {
                req.url.encodedPath.endsWith("/predictions") && req.method.value == "POST" -> respond(
                    content = """{"id":"u-3","status":"starting","urls":{"get":"https://api.replicate.com/v1/predictions/u-3"}}""",
                    status = HttpStatusCode.Created,
                    headers = jsonHeaders(),
                )
                req.url.toString().endsWith("/predictions/u-3") -> respond(
                    content = """{"id":"u-3","status":"failed","error":"model oom"}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders(),
                )
                else -> error("unexpected ${req.url}")
            }
        }
        val engine = ReplicateUpscaleEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val ex = assertFailsWith<IllegalStateException> {
            engine.upscale(UpscaleRequest(imagePath = src.absolutePath, modelId = "m", scale = 2, seed = 1L))
        }
        assertTrue(ex.message!!.contains("status=failed"))
    }

    @Test fun emptySourceFailsLoud() = runTest {
        val tmp = Files.createTempFile("upscale-empty-", ".png").toFile().also { it.deleteOnExit() }
        val http = mockHttp { _ -> error("http must not be called on empty source") }
        val engine = ReplicateUpscaleEngine(http, apiKey = "test", pollIntervalMs = 0L)
        val ex = assertFailsWith<IllegalStateException> {
            engine.upscale(UpscaleRequest(imagePath = tmp.absolutePath, modelId = "m", scale = 2, seed = 1L))
        }
        assertTrue(ex.message!!.contains("empty"))
    }
}
