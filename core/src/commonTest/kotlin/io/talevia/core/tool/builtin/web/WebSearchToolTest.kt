package io.talevia.core.tool.builtin.web

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.SearchEngine
import io.talevia.core.platform.SearchResult
import io.talevia.core.platform.SearchResults
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the tool layer in isolation from any concrete provider:
 *   - happy path with answer + multiple hits
 *   - empty result list passes through
 *   - blank query rejected
 *   - maxResults capped to HARD_MAX_RESULTS / coerced up from <1
 *   - lower-cased trimmed query as permission pattern (and "*" fallbacks)
 */
class WebSearchToolTest {
    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private class FakeSearchEngine(
        override val providerId: String = "fake",
        private val canned: SearchResults = SearchResults("", emptyList()),
    ) : SearchEngine {
        var lastQuery: String? = null
        var lastMax: Int? = null

        override suspend fun search(query: String, maxResults: Int): SearchResults {
            lastQuery = query
            lastMax = maxResults
            return canned.copy(query = query)
        }
    }

    @Test fun `returns hits and answer from underlying engine`() = runTest {
        val engine = FakeSearchEngine(
            canned = SearchResults(
                query = "",
                results = listOf(
                    SearchResult("Kotlin", "https://kotlinlang.org", "JetBrains' programming language."),
                    SearchResult("KMP", "https://kotlinlang.org/lp/multiplatform/", "Share code across platforms."),
                ),
                answer = "Kotlin is a statically typed language by JetBrains.",
            ),
        )
        val tool = WebSearchTool(engine)

        val result = tool.execute(WebSearchTool.Input(query = "kotlin language"), ctx)

        assertEquals("kotlin language", engine.lastQuery)
        assertEquals(WebSearchTool.DEFAULT_MAX_RESULTS, engine.lastMax)
        assertEquals("fake", result.data.provider)
        assertEquals(2, result.data.results.size)
        assertEquals("Kotlin", result.data.results[0].title)
        assertEquals("https://kotlinlang.org", result.data.results[0].url)
        assertEquals("Kotlin is a statically typed language by JetBrains.", result.data.answer)
        assertTrue(result.outputForLlm.contains("Kotlin"))
        assertTrue(result.outputForLlm.contains("https://kotlinlang.org"))
        assertTrue(result.outputForLlm.contains("answer:"))
    }

    @Test fun `empty result list passes through cleanly`() = runTest {
        val tool = WebSearchTool(FakeSearchEngine())
        val result = tool.execute(WebSearchTool.Input(query = "no hits"), ctx)
        assertEquals(0, result.data.results.size)
        assertNull(result.data.answer)
        assertTrue(result.outputForLlm.contains("(no results)"))
    }

    @Test fun `blank query is rejected`(): Unit = runTest {
        val tool = WebSearchTool(FakeSearchEngine())
        assertFailsWith<IllegalArgumentException> {
            tool.execute(WebSearchTool.Input(query = "   "), ctx)
        }
    }

    @Test fun `maxResults is capped to HARD_MAX_RESULTS`() = runTest {
        val engine = FakeSearchEngine()
        val tool = WebSearchTool(engine)
        tool.execute(WebSearchTool.Input(query = "x", maxResults = 1000), ctx)
        assertEquals(WebSearchTool.HARD_MAX_RESULTS, engine.lastMax)
    }

    @Test fun `maxResults below 1 is coerced up to 1`() = runTest {
        val engine = FakeSearchEngine()
        val tool = WebSearchTool(engine)
        tool.execute(WebSearchTool.Input(query = "x", maxResults = 0), ctx)
        assertEquals(1, engine.lastMax)
    }

    @Test fun `permission pattern is lower-cased trimmed query`() {
        assertEquals(
            "kotlin coroutines",
            WebSearchTool.extractQueryPattern("""{"query":"  Kotlin Coroutines  "}"""),
        )
        assertEquals(
            "site:github.com kotlin",
            WebSearchTool.extractQueryPattern("""{"query":"site:github.com KOTLIN"}"""),
        )
        assertEquals("*", WebSearchTool.extractQueryPattern("not-json"))
        assertEquals("*", WebSearchTool.extractQueryPattern("""{"query":""}"""))
        assertEquals("*", WebSearchTool.extractQueryPattern("""{"query":"   "}"""))
    }
}
