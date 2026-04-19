package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the SSE parser contract: well-formed event/data pairs, comment lines,
 * multi-line data accumulation, trailing event without terminating blank line.
 *
 * The provider hot-path relies on these behaviours — a regression here would
 * manifest as silently-dropped tool calls or hung streams.
 */
class SseParserTest {

    @Test
    fun parsesBasicEventDataBlocks() = runTest {
        val raw = """
            |event: message_start
            |data: {"type":"message_start"}
            |
            |event: message_stop
            |data: {"type":"message_stop"}
            |
            |
        """.trimMargin().replace("\n", "\r\n")
        val events = sseClient(raw).get("https://example.com").sseEvents().toList()
        assertEquals(2, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("{\"type\":\"message_start\"}", events[0].data)
        assertEquals("message_stop", events[1].event)
    }

    @Test
    fun ignoresCommentLines() = runTest {
        val raw = """
            |: this is a heartbeat comment
            |event: ping
            |data: {}
            |
            |
        """.trimMargin()
        val events = sseClient(raw).get("https://example.com").sseEvents().toList()
        assertEquals(1, events.size)
        assertEquals("ping", events[0].event)
        assertEquals("{}", events[0].data)
    }

    @Test
    fun multiLineDataAccumulates() = runTest {
        val raw = """
            |event: x
            |data: line-1
            |data: line-2
            |
            |
        """.trimMargin()
        val events = sseClient(raw).get("https://example.com").sseEvents().toList()
        assertEquals(1, events.size)
        assertEquals("line-1\nline-2", events[0].data)
    }

    @Test
    fun emitsTrailingEventWithoutBlankLine() = runTest {
        val raw = "event: tail\ndata: payload"
        val events = sseClient(raw).get("https://example.com").sseEvents().toList()
        assertEquals(1, events.size)
        assertEquals("tail", events[0].event)
        assertEquals("payload", events[0].data)
    }

    @Test
    fun unknownFieldLinesIgnored() = runTest {
        val raw = """
            |id: 123
            |retry: 1000
            |event: x
            |data: {}
            |
            |
        """.trimMargin()
        val events = sseClient(raw).get("https://example.com").sseEvents().toList()
        assertEquals(1, events.size)
        assertEquals("x", events[0].event)
    }

    @Test
    fun logMalformedSseDoesNotThrow() {
        // Behavioural contract: callers rely on logMalformedSse returning cleanly
        // regardless of input length or character content. Anything else would
        // turn a malformed-chunk warning into a stream-killing crash.
        logMalformedSse("test", "event", "x".repeat(500) + "\"bad", RuntimeException("mock"))
        logMalformedSse("test", null, "", RuntimeException("no event name"))
        logMalformedSse("test", "", "\n\r\t", IllegalArgumentException())
        assertTrue(true)
    }

    private fun sseClient(body: String): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
        }
    }
}
