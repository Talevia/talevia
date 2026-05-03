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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runPartsQuery] —
 * `core/tool/builtin/session/query/PartsQuery.kt`. The
 * SessionQueryTool's `select=parts` handler returning
 * one row per Part in a session, most-recent first.
 * Cycle 183 audit: 71 LOC, 0 direct test refs (used
 * through full-tool integration but the kind-validation,
 * includeCompacted-defaults-true, sort, and 24-char
 * preview-clamp contracts were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`kind` filter validates against `VALID_PART_KINDS`
 *    (11 kinds) or throws.** Drift to "silent unknown
 *    kind → empty list" would mask agent typos.
 *
 * 2. **`includeCompacted` filter: null → TRUE (DEFAULT
 *    INCLUDE compacted parts).** Sister to SessionsQuery's
 *    `includeArchived` which defaults to FALSE — these
 *    two flags have OPPOSITE defaults, intentionally:
 *    archived sessions are usually hidden but compacted
 *    parts are still load-bearing context. Drift to "null
 *    means false" would silently drop compacted history.
 *
 * 3. **Sort is `createdAt` DESCENDING.** Drift to ascending
 *    would break the documented "most-recent first"
 *    guarantee that drives every part-list rendering.
 */
class PartsQueryTest {

    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = baseTime,
                updatedAt = baseTime,
            ),
        )
        return sessionId
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
        createdAtEpochMs: Long,
        compactedAt: Instant? = null,
    ) {
        store.upsertPart(
            Part.Text(
                id = PartId(partId),
                messageId = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                compactedAt = compactedAt,
                text = text,
            ),
        )
    }

    private fun input(
        sessionId: String?,
        kind: String? = null,
        includeCompacted: Boolean? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_PARTS,
        sessionId = sessionId,
        kind = kind,
        includeCompacted = includeCompacted,
    )

    private fun rowFields(rows: kotlinx.serialization.json.JsonArray, key: String): List<String> =
        rows.map { row ->
            row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
        }

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runPartsQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue("requires sessionId" in (ex.message ?: ""))
    }

    // ── kind filter validation ───────────────────────────────

    @Test fun unknownKindThrowsWithValidKindsList() = runTest {
        // Marquee kind-validation pin: drift to "silent
        // unknown → empty list" would mask agent typos
        // ("texxt" instead of "text") with a plausible-
        // looking empty result.
        val store = newStore()
        seedSession(store, "s1")

        val ex = assertFailsWith<IllegalStateException> {
            runPartsQuery(
                store,
                input("s1", kind = "texxt"),
                limit = 100,
                offset = 0,
            )
        }
        assertTrue(
            "kind must be one of" in (ex.message ?: ""),
            "expected validator phrase; got: ${ex.message}",
        )
        // Some valid kinds appear in the message so the
        // agent can self-correct.
        assertTrue(
            "text" in (ex.message ?: "") || "tool" in (ex.message ?: ""),
            "expected valid-kinds list cited; got: ${ex.message}",
        )
        // The bad input is echoed.
        assertTrue(
            "texxt" in (ex.message ?: ""),
            "expected bad input cited; got: ${ex.message}",
        )
    }

    @Test fun blankKindIsTreatedAsNoFilter() = runTest {
        // Pin: per `input.kind?.takeIf { it.isNotBlank() }`,
        // empty / whitespace kind resolves to no-filter.
        // Drift to "literal-empty matches nothing" would
        // fail every "all kinds" query when the agent
        // accidentally sends "".
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p1",
            text = "hello", createdAtEpochMs = 100L,
        )

        val resultEmpty = runPartsQuery(
            store,
            input("s1", kind = ""),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, resultEmpty.data.total, "empty kind filter is no-filter")

        val resultBlank = runPartsQuery(
            store,
            input("s1", kind = "   "),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, resultBlank.data.total, "whitespace kind filter is no-filter")
    }

    @Test fun textKindFilterReturnsOnlyTextParts() = runTest {
        // Pin: kind="text" filters to text parts only.
        // Sister kinds (tool, todos, etc.) excluded.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p-text",
            text = "hello", createdAtEpochMs = 100L,
        )
        // Plant a Reasoning part (different kind).
        store.upsertPart(
            Part.Reasoning(
                id = PartId("p-reason"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = baseTime,
                text = "thinking",
            ),
        )

        val result = runPartsQuery(
            store,
            input("s1", kind = "text"),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, result.data.total, "only text parts")
        assertEquals("p-text", rowFields(result.data.rows, "partId").single())
    }

    // ── includeCompacted filter ──────────────────────────────

    @Test fun includeCompactedNullDefaultsToTrueIncludeCompacted() = runTest {
        // Marquee includeCompacted-default pin: per
        // `input.includeCompacted ?: true`, the default
        // INCLUDES compacted parts. Sister to
        // SessionsQuery's `includeArchived` which
        // DEFAULTS TO FALSE — opposite defaults are
        // intentional (compacted parts are still load-
        // bearing context).
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p-active",
            text = "active", createdAtEpochMs = 100L,
        )
        seedTextPart(
            store, "s1", "m1", partId = "p-compacted",
            text = "compacted", createdAtEpochMs = 50L,
            compactedAt = baseTime,
        )

        val result = runPartsQuery(
            store,
            input("s1", includeCompacted = null),
            limit = 100,
            offset = 0,
        )
        assertEquals(
            2,
            result.data.total,
            "default null → include compacted (BOTH parts surface)",
        )
    }

    @Test fun includeCompactedFalseExcludesCompacted() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p-active",
            text = "active", createdAtEpochMs = 100L,
        )
        seedTextPart(
            store, "s1", "m1", partId = "p-compacted",
            text = "compacted", createdAtEpochMs = 50L,
            compactedAt = baseTime,
        )

        val result = runPartsQuery(
            store,
            input("s1", includeCompacted = false),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, result.data.total, "only active parts")
        assertEquals("p-active", rowFields(result.data.rows, "partId").single())
    }

    @Test fun includeCompactedTrueIncludesCompacted() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p-active",
            text = "active", createdAtEpochMs = 100L,
        )
        seedTextPart(
            store, "s1", "m1", partId = "p-compacted",
            text = "compacted", createdAtEpochMs = 50L,
            compactedAt = baseTime,
        )

        val result = runPartsQuery(
            store,
            input("s1", includeCompacted = true),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, result.data.total, "explicit true includes compacted")
    }

    // ── Sort: createdAt DESC ─────────────────────────────────

    @Test fun sortIsCreatedAtDescendingMostRecentFirst() = runTest {
        // Marquee sort-order pin. Drift to ascending would
        // break the documented "most recent first" guarantee.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(store, "s1", "m1", partId = "old", text = "o", createdAtEpochMs = 1L)
        seedTextPart(store, "s1", "m1", partId = "newest", text = "n", createdAtEpochMs = 3L)
        seedTextPart(store, "s1", "m1", partId = "mid", text = "m", createdAtEpochMs = 2L)

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        assertContentEquals(
            listOf("newest", "mid", "old"),
            rowFields(result.data.rows, "partId"),
            "createdAt DESC most-recent first",
        )
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsPostSort() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedTextPart(
                store, "s1", "m1", partId = "p-$i",
                text = "t$i", createdAtEpochMs = i.toLong(),
            )
        }

        val result = runPartsQuery(store, input("s1"), limit = 2, offset = 0)
        assertEquals(5, result.data.total)
        assertEquals(2, result.data.returned)
        // Most-recent 2: p-5, p-4.
        assertContentEquals(
            listOf("p-5", "p-4"),
            rowFields(result.data.rows, "partId"),
        )
    }

    @Test fun paginationOffsetSkipsAfterSort() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedTextPart(
                store, "s1", "m1", partId = "p-$i",
                text = "t$i", createdAtEpochMs = i.toLong(),
            )
        }

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 2)
        assertEquals(5, result.data.total)
        assertEquals(3, result.data.returned)
        // Skip newest 2 (p-5, p-4); next 3 are p-3, p-2, p-1.
        assertContentEquals(
            listOf("p-3", "p-2", "p-1"),
            rowFields(result.data.rows, "partId"),
        )
    }

    // ── PartRow shape + preview ───────────────────────────────

    @Test fun partRowExposesAllDocumentedFields() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.upsertPart(
            Part.Text(
                id = PartId("p1"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = createdAt,
                compactedAt = null,
                text = "hello world",
            ),
        )

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"partId\":\"p1\"" in rowJson, "partId; got: $rowJson")
        assertTrue("\"kind\":\"text\"" in rowJson, "kind; got: $rowJson")
        assertTrue("\"messageId\":\"m1\"" in rowJson, "messageId; got: $rowJson")
        assertTrue(
            "\"createdAtEpochMs\":${createdAt.toEpochMilliseconds()}" in rowJson,
        )
        assertTrue("\"preview\":\"hello world\"" in rowJson, "preview; got: $rowJson")
        // compactedAtEpochMs is null/absent for non-compacted.
        assertTrue(
            "\"compactedAtEpochMs\":null" in rowJson || "\"compactedAtEpochMs\":" !in rowJson,
            "compactedAtEpochMs null/absent; got: $rowJson",
        )
    }

    @Test fun compactedPartRowHasCompactedAtEpochMs() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val compactedAt = Instant.fromEpochMilliseconds(1_700_000_010_000L)
        seedTextPart(
            store, "s1", "m1", partId = "p1",
            text = "x", createdAtEpochMs = 100L,
            compactedAt = compactedAt,
        )

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue(
            "\"compactedAtEpochMs\":${compactedAt.toEpochMilliseconds()}" in rowJson,
            "compactedAtEpochMs surfaces; got: $rowJson",
        )
    }

    // ── outputForLlm format conventions ──────────────────────

    @Test fun emptyResultBodyOmitsKindWhenNoFilter() = runTest {
        val store = newStore()
        seedSession(store, "s1")

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        // "No parts on session s1." — no kind suffix.
        assertEquals("No parts on session s1.", result.outputForLlm)
    }

    @Test fun emptyResultBodyAppendsKindScopeWhenFiltering() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        // Add a non-text part so kind=text returns empty.
        seedAssistantMessage(store, "s1", "m1")
        store.upsertPart(
            Part.Reasoning(
                id = PartId("p-r"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = baseTime,
                text = "thinking",
            ),
        )

        val result = runPartsQuery(
            store,
            input("s1", kind = "text"),
            limit = 100,
            offset = 0,
        )
        assertEquals("No parts on session s1 kind=text.", result.outputForLlm)
    }

    @Test fun nonEmptyBodyCitesCountAndKindPreviewForFirstFiveOnly() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedTextPart(
            store, "s1", "m1", partId = "p1", text = "first message",
            createdAtEpochMs = 100L,
        )
        seedTextPart(
            store, "s1", "m1", partId = "p2", text = "second message",
            createdAtEpochMs = 50L,
        )

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        assertTrue("2 of 2" in result.outputForLlm)
        assertTrue("part(s)" in result.outputForLlm)
        assertTrue("most recent first" in result.outputForLlm)
        // "kind:preview-clamp" format per row.
        assertTrue(
            "text:first message" in result.outputForLlm,
            "row preview format; got: ${result.outputForLlm}",
        )
    }

    @Test fun previewIsClampedTo24CharsInBody() = runTest {
        // Pin: body uses `it.preview.take(24)` so even
        // long previews surface as 24-char prefixes in the
        // body summary. Drift would inflate the LLM's
        // context with full preview strings.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val longText = "a".repeat(80)
        seedTextPart(
            store, "s1", "m1", partId = "p1",
            text = longText, createdAtEpochMs = 100L,
        )

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        // The body has "text:" + 24 chars. The full text
        // appears in the rows JSON (preview field) up to
        // PREVIEW_CHARS=80.
        val expectedPrefix = "text:" + "a".repeat(24)
        assertTrue(
            expectedPrefix in result.outputForLlm,
            "body preview is clamped to 24 chars; got: ${result.outputForLlm}",
        )
        // 25 'a's after the colon would NOT appear.
        assertTrue(
            "text:" + "a".repeat(25) !in result.outputForLlm,
            "body preview does NOT extend to 25 chars",
        )
    }

    @Test fun bodyTruncatesAfterFiveRowsWithEllipsis() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..7) {
            seedTextPart(
                store, "s1", "m1", partId = "p-$i",
                text = "t$i", createdAtEpochMs = i.toLong(),
            )
        }

        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(7, result.data.total)
        assertTrue(result.outputForLlm.endsWith("; …"))
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsParts() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        val result = runPartsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_PARTS, result.data.select)
    }
}
