package io.talevia.core.tool.builtin.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers happy-path HTML + JSON fetch, HTML stripping, binary refusal,
 * HTTP error surfacing, URL validation, byte-cap truncation, and the
 * host-based permission pattern extraction.
 */
class WebFetchToolTest {
    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun clientReturning(
        body: String,
        contentType: String = "text/html; charset=utf-8",
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        return HttpClient(engine)
    }

    @Test fun `strips html tags and decodes common entities`() = runTest {
        val html = """
            <html><head><style>.x{color:red}</style><script>alert(1)</script></head>
            <body><h1>Hello &amp; goodbye</h1><p>Para one.</p><p>Para two.</p></body></html>
        """.trimIndent()
        val tool = WebFetchTool(clientReturning(html))

        val result = tool.execute(
            WebFetchTool.Input(url = "https://example.com/doc"),
            ctx,
        )
        assertEquals(200, result.data.status)
        assertFalse(result.data.truncated)
        assertTrue(result.data.content.contains("Hello & goodbye"))
        assertTrue(result.data.content.contains("Para one."))
        assertTrue(result.data.content.contains("Para two."))
        assertFalse(result.data.content.contains("<h1>"))
        assertFalse(result.data.content.contains("alert(1)"), "script body should be dropped")
        assertFalse(result.data.content.contains(".x{color:red}"), "style body should be dropped")
    }

    @Test fun `plain json passes through unchanged`() = runTest {
        val body = """{"a":1,"b":["x","y"]}"""
        val tool = WebFetchTool(
            clientReturning(body = body, contentType = "application/json"),
        )
        val result = tool.execute(
            WebFetchTool.Input(url = "https://api.example.com/v1/x"),
            ctx,
        )
        assertEquals(body, result.data.content)
        assertEquals("application/json", result.data.contentType)
    }

    @Test fun `binary content type is refused`(): Unit = runTest {
        val tool = WebFetchTool(
            clientReturning(body = "PNG-bytes", contentType = "image/png"),
        )
        assertFailsWith<IllegalArgumentException> {
            tool.execute(WebFetchTool.Input(url = "https://example.com/p.png"), ctx)
        }
    }

    @Test fun `non-2xx response surfaces as error`(): Unit = runTest {
        val tool = WebFetchTool(
            clientReturning(
                body = "not found",
                contentType = "text/plain",
                status = HttpStatusCode.NotFound,
            ),
        )
        val err = assertFailsWith<IllegalArgumentException> {
            tool.execute(WebFetchTool.Input(url = "https://example.com/missing"), ctx)
        }
        assertTrue(err.message!!.contains("404"), "error should mention status; was: ${err.message}")
    }

    @Test fun `rejects non-http schemes and empty url`(): Unit = runTest {
        val tool = WebFetchTool(clientReturning("x", "text/plain"))
        assertFailsWith<IllegalArgumentException> {
            tool.execute(WebFetchTool.Input(url = "   "), ctx)
        }
        assertFailsWith<IllegalArgumentException> {
            tool.execute(WebFetchTool.Input(url = "ftp://example.com/x"), ctx)
        }
    }

    @Test fun `truncates oversized responses and sets truncated flag`() = runTest {
        val big = "x".repeat(2000)
        val tool = WebFetchTool(clientReturning(body = big, contentType = "text/plain"))

        val result = tool.execute(
            WebFetchTool.Input(url = "https://example.com/big", maxBytes = 500),
            ctx,
        )
        assertTrue(result.data.truncated)
        assertEquals(500, result.data.content.length)
    }

    @Test fun `permission pattern extracts host from url`() {
        assertEquals(
            "github.com",
            WebFetchTool.extractHostPattern("""{"url":"https://github.com/anthropic/foo"}"""),
        )
        assertEquals(
            "docs.kernel.org",
            WebFetchTool.extractHostPattern("""{"url":"https://docs.kernel.org/path?q=1"}"""),
        )
        assertEquals("*", WebFetchTool.extractHostPattern("not-json"))
        assertEquals("*", WebFetchTool.extractHostPattern("""{"url":""}"""))
    }

    @Test fun `html stripper drops comments and collapses whitespace`() {
        val html = "<!-- drop -->\n<p>Hello    world</p>\n\n\n\n<p>Again</p>"
        val out = WebFetchTool.stripHtml(html)
        assertFalse(out.contains("drop"))
        assertTrue(out.contains("Hello world"))
        assertFalse(out.contains("\n\n\n"), "should collapse blank runs; got:\n$out")
    }
}
