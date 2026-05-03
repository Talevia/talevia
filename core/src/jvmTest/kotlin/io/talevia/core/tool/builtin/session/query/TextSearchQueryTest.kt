package io.talevia.core.tool.builtin.session.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runTextSearchQuery] —
 * `core/tool/builtin/session/query/TextSearchQuery.kt`. The
 * `session_query(select=text_search)` substring search over
 * `Part.Text` content. Cycle 214 audit: 124 LOC, 0 direct test
 * refs (only via the `SessionQueryTool` dispatcher).
 *
 * Same audit-pattern fallback as cycles 207-213. Uses the real
 * SqlDelight in-memory session store (per the sibling
 * `AncestorsQueryTest` / `PartsQueryTest` pattern), so the SQL
 * LIKE pagination + the helper's snippet windowing arithmetic
 * are tested end-to-end.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Blank query rejected.** Empty / whitespace-only `query`
 *     fails fast with a remediation hint. Drift to "silent return
 *     all parts" would spike token cost.
 *
 *  2. **Cross-session vs scoped search.** `sessionId == null` →
 *     scopeNote "across all sessions" + searches across all
 *     stored parts. Non-null → "in session <sid>" + only that
 *     session's parts.
 *
 *  3. **Snippet windowing: ±SNIPPET_PAD chars around first hit.**
 *     SNIPPET_PAD = 60. When the match is in the middle of a
 *     long part, the snippet is `"…<60 chars before>match<60
 *     chars after>…"`. Drift to "fixed start=0" would always
 *     return the head of long messages and miss the actual hit.
 *
 *  4. **Ellipsis prefix/suffix conditional.** `…` prefix only
 *     when `start > 0`; `…` suffix only when `end < text.length`.
 *     Short matches near start/end of the part don't get fake
 *     ellipsis.
 *
 *  5. **`matchOffset` is position within snippet, not original
 *     text.** Per kdoc: "matchOffset is the position of the match
 *     within the returned snippet." Off-by-one drift would
 *     highlight the wrong characters in any UI.
 *
 *  6. **Case-insensitive match.** Uppercase query matches
 *     lowercase text and vice-versa (mirrors SQL LIKE — the SQL
 *     side filters case-insensitively, the snippet helper
 *     lowercases both sides for the `indexOf`).
 *
 * Plus shape pins: title format with singular/plural; summary
 * format for empty / non-empty; output `select` echoes
 * SELECT_TEXT_SEARCH; `total` and `returned` both equal `rows.size`.
 */
class TextSearchQueryTest {

    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(store: SqlDelightSessionStore, sid: String) {
        store.createSession(
            Session(
                id = SessionId(sid),
                projectId = ProjectId("p"),
                title = "session-$sid",
                parentId = null,
                createdAt = baseTime,
                updatedAt = baseTime,
                archived = false,
            ),
        )
    }

    private suspend fun seedAssistantMessage(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId("u-$mid"),
                sessionId = SessionId(sid),
                createdAt = baseTime,
                agent = "test",
                model = ModelRef("anthropic", "claude"),
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = baseTime,
                parentId = MessageId("u-$mid"),
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    private suspend fun seedTextPart(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        partId: String,
        text: String,
        createdAtEpochMs: Long = 1_700_000_000_000L,
    ) {
        store.upsertPart(
            Part.Text(
                id = PartId(partId),
                messageId = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                text = text,
            ),
        )
    }

    /**
     * Convenience: seed a session + assistant message + one text part.
     * Most cases need exactly this shape.
     */
    private suspend fun seedSinglePart(
        store: SqlDelightSessionStore,
        sid: String,
        text: String,
        partId: String = "part-$sid",
        mid: String = "m-$sid",
    ) {
        seedSession(store, sid)
        seedAssistantMessage(store, sid, mid)
        seedTextPart(store, sid, mid, partId, text)
    }

    private fun input(
        query: String? = "needle",
        sessionId: String? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_TEXT_SEARCH,
        query = query,
        sessionId = sessionId,
    )

    private fun rowAt(json: JsonArray, idx: Int): JsonObject = json[idx].jsonObject

    // ── 1. Blank query rejected ─────────────────────────────

    @Test fun blankQueryRejected() = runTest {
        val store = newStore()
        for (blank in listOf(null, "", " ", "\t", "\n  ")) {
            val ex = assertFailsWith<IllegalStateException> {
                runTextSearchQuery(store, input(query = blank), limit = 50, offset = 0)
            }
            val msg = ex.message ?: ""
            assertTrue(
                "select='${SessionQueryTool.SELECT_TEXT_SEARCH}' requires non-blank `query`" in msg,
                "expected select-cited remediation for query=${blank ?: "null"}; got: $msg",
            )
            assertTrue(
                "Pass the substring you want to find" in msg,
                "expected substring hint; got: $msg",
            )
        }
    }

    // ── 2. Cross-session vs scoped ──────────────────────────

    @Test fun nullSessionIdSearchesAcrossAllSessions() = runTest {
        val store = newStore()
        seedSinglePart(store, "s-A", "needle in session A")
        seedSinglePart(store, "s-B", "needle in session B")
        val result = runTextSearchQuery(
            store,
            input(query = "needle", sessionId = null),
            limit = 50,
            offset = 0,
        )
        val rows = result.data.rows as JsonArray
        assertEquals(2, rows.size, "both sessions surface")
        assertTrue(
            "across all sessions" in result.outputForLlm,
            "expected 'across all sessions' scope; got: ${result.outputForLlm}",
        )
    }

    @Test fun explicitSessionIdScopesSearch() = runTest {
        val store = newStore()
        seedSinglePart(store, "s-A", "needle in session A")
        seedSinglePart(store, "s-B", "needle in session B")
        val result = runTextSearchQuery(
            store,
            input(query = "needle", sessionId = "s-A"),
            limit = 50,
            offset = 0,
        )
        val rows = result.data.rows as JsonArray
        assertEquals(1, rows.size, "only s-A surfaces")
        assertEquals("s-A", rows[0].jsonObject["sessionId"]!!.jsonPrimitive.content)
        assertTrue(
            "in session s-A" in result.outputForLlm,
            "expected scope cited; got: ${result.outputForLlm}",
        )
    }

    // ── 3. Snippet windowing ─────────────────────────────────

    @Test fun matchInMiddleOfLongTextProducesWindowedSnippetWithEllipses() = runTest {
        val before = "x".repeat(100)
        val after = "y".repeat(100)
        val text = before + "NEEDLE" + after
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val snippet = (result.data.rows as JsonArray)[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertTrue(snippet.startsWith("…"), "prefix ellipsis when start > 0")
        assertTrue(snippet.endsWith("…"), "suffix ellipsis when end < text.length")
        assertTrue("NEEDLE" in snippet, "snippet contains the matched text (preserves original case)")
        // Window: 60 before + needle + 60 after + 2 ellipsis chars = 60 + 6 + 60 + 2 = 128
        assertEquals(128, snippet.length, "window is ±60 + needle + 2 ellipsis chars")
    }

    @Test fun matchAtStartHasNoPrefixEllipsis() = runTest {
        val text = "needle followed by some text " + "y".repeat(100)
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val snippet = (result.data.rows as JsonArray)[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertTrue(!snippet.startsWith("…"), "no prefix ellipsis when start=0; got: '$snippet'")
        assertTrue(snippet.endsWith("…"), "suffix ellipsis still present when end < text.length")
        assertTrue(snippet.startsWith("needle"), "snippet starts with the matched text")
    }

    @Test fun matchAtEndHasNoSuffixEllipsis() = runTest {
        val text = "x".repeat(100) + " ends with needle"
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val snippet = (result.data.rows as JsonArray)[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertTrue(snippet.startsWith("…"), "prefix ellipsis when start > 0")
        assertTrue(!snippet.endsWith("…"), "no suffix ellipsis when end = text.length; got: '$snippet'")
    }

    @Test fun shortTextWithMatchHasNoEllipsesEither() = runTest {
        val text = "tiny needle here"
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val snippet = (result.data.rows as JsonArray)[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertEquals(text, snippet, "short text → snippet = text verbatim, no ellipses")
    }

    // ── 4. matchOffset arithmetic ───────────────────────────

    @Test fun matchOffsetReportsPositionWithinSnippetNotOriginal() = runTest {
        // Marquee matchOffset pin: per kdoc, matchOffset is position
        // within the SNIPPET. UI can highlight without re-searching.
        val before = "x".repeat(100)
        val text = before + "NEEDLE" + "y".repeat(20)
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val row = (result.data.rows as JsonArray)[0].jsonObject
        val snippet = row["snippet"]!!.jsonPrimitive.content
        val matchOffset = row["matchOffset"]!!.jsonPrimitive.content.toInt()
        // Verify: snippet[matchOffset..matchOffset+5] == "NEEDLE".
        assertEquals(
            "NEEDLE",
            snippet.substring(matchOffset, matchOffset + 6),
            "matchOffset $matchOffset within snippet '$snippet' must point at the match",
        )
    }

    @Test fun matchOffsetAtStartIsZeroWhenNoPrefixEllipsis() = runTest {
        val text = "needle here at the start"
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val row = (result.data.rows as JsonArray)[0].jsonObject
        assertEquals(0, row["matchOffset"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun matchOffsetAfterEllipsisAccountsForOneCharPrefix() = runTest {
        // Pin: prefix ellipsis is ONE char ("…", a single Unicode
        // char). matchOffset is shifted by that one char.
        val before = "x".repeat(100)
        val text = before + "needle"
        val store = newStore()
        seedSinglePart(store, "s1", text)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val row = (result.data.rows as JsonArray)[0].jsonObject
        val matchOffset = row["matchOffset"]!!.jsonPrimitive.content.toInt()
        // matchOffset = prefix.length (1) + (idx - start).
        // idx = 100, start = 100 - 60 = 40, idx - start = 60.
        // → matchOffset = 1 + 60 = 61.
        assertEquals(
            61,
            matchOffset,
            "matchOffset = ellipsis.length(1) + (idx - start)",
        )
    }

    // ── 5. Case-insensitive match ───────────────────────────

    @Test fun caseInsensitiveMatchUppercaseQueryMatchesLowercaseText() = runTest {
        val store = newStore()
        seedSinglePart(store, "s1", text = "the agent saw a banana")
        val result = runTextSearchQuery(store, input(query = "AGENT"), limit = 50, offset = 0)
        val rows = result.data.rows as JsonArray
        assertEquals(1, rows.size, "case-insensitive match")
        // Snippet preserves ORIGINAL case (the kdoc-implied UI hint).
        val snippet = rows[0].jsonObject["snippet"]!!.jsonPrimitive.content
        assertTrue("agent" in snippet, "snippet preserves original case")
    }

    @Test fun caseInsensitiveMatchLowercaseQueryMatchesMixedCaseText() = runTest {
        val store = newStore()
        seedSinglePart(store, "s1", text = "The Agent System")
        val result = runTextSearchQuery(store, input(query = "agent"), limit = 50, offset = 0)
        assertEquals(1, (result.data.rows as JsonArray).size)
    }

    // ── 6. Output shape pins ────────────────────────────────

    @Test fun titleFormatSingularVsPluralMatch() = runTest {
        // Singular: one match.
        val store1 = newStore()
        seedSinglePart(store1, "s1", "needle here")
        val r1 = runTextSearchQuery(store1, input(query = "needle"), limit = 50, offset = 0)
        assertTrue(
            "(1 match)" in r1.title && "(1 matches)" !in r1.title,
            "singular: '(1 match)'; got: ${r1.title}",
        )
        // Zero: plural.
        val store0 = newStore()
        val r0 = runTextSearchQuery(store0, input(query = "needle"), limit = 50, offset = 0)
        assertTrue("(0 matches)" in r0.title, "zero: '(0 matches)' (plural); got: ${r0.title}")
        // Three: plural.
        val store3 = newStore()
        seedSession(store3, "s")
        seedAssistantMessage(store3, "s", "m")
        seedTextPart(store3, "s", "m", "p1", "needle one")
        seedTextPart(store3, "s", "m", "p2", "needle two")
        seedTextPart(store3, "s", "m", "p3", "needle three")
        val r3 = runTextSearchQuery(store3, input(query = "needle"), limit = 50, offset = 0)
        assertTrue("(3 matches)" in r3.title, "three: '(3 matches)' (plural); got: ${r3.title}")
    }

    @Test fun emptyResultsSummaryFormat() = runTest {
        val store = newStore()
        seedSession(store, "s-99")
        val result = runTextSearchQuery(
            store,
            input(query = "ghost", sessionId = "s-99"),
            limit = 50,
            offset = 0,
        )
        assertEquals(
            "No text-part matches for query 'ghost' in session s-99.",
            result.outputForLlm,
        )
    }

    @Test fun nonEmptyResultsSummaryFormat() = runTest {
        val store = newStore()
        seedSession(store, "s")
        seedAssistantMessage(store, "s", "m")
        seedTextPart(store, "s", "m", "p1", "needle one")
        seedTextPart(store, "s", "m", "p2", "needle two")
        // Use offset=0 to ensure rows surface (offset=5 over 2 total
        // would yield zero rows and trigger the no-matches branch).
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 25, offset = 0)
        val msg = result.outputForLlm
        assertTrue("Found 2 text-part match(es) for 'needle' across all sessions" in msg, "got: $msg")
        assertTrue("(returning 2 from offset=0, limit=25)" in msg, "got: $msg")
    }

    @Test fun outputDataSelectAndTotalsEqual() = runTest {
        val store = newStore()
        seedSession(store, "s")
        seedAssistantMessage(store, "s", "m")
        seedTextPart(store, "s", "m", "p1", "needle one")
        seedTextPart(store, "s", "m", "p2", "needle two")
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        assertEquals(SessionQueryTool.SELECT_TEXT_SEARCH, result.data.select)
        assertEquals(2, result.data.total, "total = rows.size (pagination is store-side)")
        assertEquals(2, result.data.returned, "returned = rows.size")
        val rows = result.data.rows
        assertEquals(2, rows.size, "rows is a JsonArray; size matches the seeded count")
    }

    @Test fun rowCarriesAllFields() = runTest {
        val createdMs = 1_700_000_123_456L
        val store = newStore()
        seedSession(store, "session-99")
        seedAssistantMessage(store, "session-99", "msg-42")
        seedTextPart(store, "session-99", "msg-42", "part-99", "the needle in the haystack", createdAtEpochMs = createdMs)
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 50, offset = 0)
        val row = (result.data.rows as JsonArray)[0].jsonObject
        assertEquals("part-99", row["partId"]!!.jsonPrimitive.content)
        assertEquals("msg-42", row["messageId"]!!.jsonPrimitive.content)
        assertEquals("session-99", row["sessionId"]!!.jsonPrimitive.content)
        assertEquals(createdMs, row["createdAtEpochMs"]!!.jsonPrimitive.content.toLong())
    }

    // ── Pagination passthrough ──────────────────────────────

    @Test fun limitCapsResultCount() = runTest {
        // Pin: SQL `LIMIT` applies — only N rows returned even when
        // more match. The store does the pagination; the helper
        // surfaces what came back.
        val store = newStore()
        seedSession(store, "s")
        seedAssistantMessage(store, "s", "m")
        for (i in 1..5) {
            seedTextPart(store, "s", "m", "p$i", "needle row $i")
        }
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 3, offset = 0)
        assertEquals(3, (result.data.rows as JsonArray).size)
        assertTrue("limit=3" in result.outputForLlm, "limit cited in summary")
    }

    @Test fun offsetSkipsPriorRows() = runTest {
        // Pin: SQL `OFFSET` applies. With limit=10 + offset=2 over
        // 5 rows, we get rows 3-5 (3 results). The store's sort is
        // newest-first; with all parts at identical createdAt the
        // store falls back to a deterministic tiebreaker (partId).
        val store = newStore()
        seedSession(store, "s")
        seedAssistantMessage(store, "s", "m")
        for (i in 1..5) {
            seedTextPart(store, "s", "m", "p$i", "needle row $i")
        }
        val result = runTextSearchQuery(store, input(query = "needle"), limit = 10, offset = 2)
        assertEquals(3, (result.data.rows as JsonArray).size, "5 rows minus offset=2 → 3")
        assertTrue("offset=2" in result.outputForLlm, "offset cited in summary")
    }
}
