package io.talevia.core.provider.tavily

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format checks for the Tavily provider — what we send (request body shape,
 * endpoint, content-type) and what we accept back (Tavily's actual JSON shape,
 * including missing/null answer + skipping malformed result entries).
 */
class TavilySearchEngineTest {

    private fun mockClient(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        captureRequest: ((path: String, body: String, contentType: String?) -> Unit)? = null,
    ): HttpClient {
        val engine = MockEngine { request ->
            captureRequest?.invoke(
                request.url.encodedPath,
                request.body.toByteReadPacketSafe(),
                request.body.contentType?.toString(),
            )
            respond(
                content = responseBody,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine)
    }

    private fun io.ktor.http.content.OutgoingContent.toByteReadPacketSafe(): String =
        when (this) {
            is io.ktor.http.content.TextContent -> text
            is io.ktor.http.content.ByteArrayContent -> bytes().toString(Charsets.UTF_8)
            else -> ""
        }

    @Test fun `posts to correct endpoint with expected body shape`() = runTest {
        var capturedPath: String? = null
        var capturedBody: String? = null
        var capturedContentType: String? = null
        val client = mockClient(
            responseBody = """{"results":[],"answer":null}""",
        ) { p, b, ct ->
            capturedPath = p
            capturedBody = b
            capturedContentType = ct
        }
        val engine = TavilySearchEngine(client, apiKey = "tvly-test")

        engine.search("kotlin coroutines", maxResults = 3)

        assertEquals("/search", capturedPath)
        assertTrue(capturedContentType!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(capturedBody!!).jsonObject
        assertEquals("tvly-test", body["api_key"]?.jsonPrimitive?.content)
        assertEquals("kotlin coroutines", body["query"]?.jsonPrimitive?.content)
        assertEquals("basic", body["search_depth"]?.jsonPrimitive?.content)
        assertEquals("true", body["include_answer"]?.jsonPrimitive?.content)
        assertEquals("3", body["max_results"]?.jsonPrimitive?.content)
    }

    @Test fun `parses tavily response into SearchResults`() = runTest {
        val response = """
            {
              "query": "kotlin",
              "answer": "Kotlin is a JetBrains language.",
              "results": [
                {"title":"Kotlin","url":"https://kotlinlang.org","content":"JB lang","score":0.9},
                {"title":"KMP","url":"https://kotlinlang.org/lp/multiplatform/","content":"share code","score":0.8}
              ]
            }
        """.trimIndent()
        val engine = TavilySearchEngine(mockClient(response), apiKey = "tvly-test")

        val out = engine.search("kotlin", maxResults = 5)

        assertEquals("kotlin", out.query)
        assertEquals("Kotlin is a JetBrains language.", out.answer)
        assertEquals(2, out.results.size)
        assertEquals("Kotlin", out.results[0].title)
        assertEquals("https://kotlinlang.org", out.results[0].url)
        assertEquals("JB lang", out.results[0].snippet)
    }

    @Test fun `handles missing answer field`() = runTest {
        val response = """{"results":[{"title":"x","url":"https://x.com","content":"y"}]}"""
        val engine = TavilySearchEngine(mockClient(response), apiKey = "tvly-test")
        val out = engine.search("x", maxResults = 1)
        assertNull(out.answer)
        assertEquals(1, out.results.size)
    }

    @Test fun `handles explicit null answer field`() = runTest {
        val response = """{"answer":null,"results":[]}"""
        val engine = TavilySearchEngine(mockClient(response), apiKey = "tvly-test")
        val out = engine.search("x", maxResults = 1)
        assertNull(out.answer)
        assertEquals(0, out.results.size)
    }

    @Test fun `skips result entries missing title or url`() = runTest {
        val response = """
            {
              "results": [
                {"title":"good","url":"https://ok.com","content":"snip"},
                {"url":"https://no-title.com","content":"x"},
                {"title":"no-url","content":"x"}
              ]
            }
        """.trimIndent()
        val engine = TavilySearchEngine(mockClient(response), apiKey = "tvly-test")
        val out = engine.search("x", maxResults = 5)
        assertEquals(1, out.results.size)
        assertEquals("good", out.results[0].title)
    }

    @Test fun `surfaces non-2xx as error`(): Unit = runTest {
        val engine = TavilySearchEngine(
            mockClient(responseBody = "denied", status = HttpStatusCode.Unauthorized),
            apiKey = "tvly-bad",
        )
        val err = assertFailsWith<IllegalStateException> {
            engine.search("x", maxResults = 1)
        }
        assertTrue(err.message!!.contains("401"))
    }

    @Test fun `rejects blank query and out-of-range maxResults`(): Unit = runTest {
        val engine = TavilySearchEngine(mockClient("""{"results":[]}"""), apiKey = "tvly")
        assertFailsWith<IllegalArgumentException> { engine.search("  ", maxResults = 5) }
        assertFailsWith<IllegalArgumentException> { engine.search("x", maxResults = 0) }
        assertFailsWith<IllegalArgumentException> { engine.search("x", maxResults = 21) }
    }
}
