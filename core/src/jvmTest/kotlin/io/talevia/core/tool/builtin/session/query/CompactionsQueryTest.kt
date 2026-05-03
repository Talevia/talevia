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
 * Direct tests for [runCompactionsQuery] —
 * `core/tool/builtin/session/query/CompactionsQuery.kt`.
 * The SessionQueryTool's `select=compactions` handler
 * returning one row per Part.Compaction in a session,
 * most-recent first. Cycle 188 audit: 78 LOC, 0 direct
 * test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`summaryText` is the FULL summary, NOT truncated.**
 *    Per kdoc: "Full summary produced by the compactor —
 *    not truncated, unlike `select=parts` preview." This
 *    is the whole reason the dedicated select exists
 *    (raw `select=parts&kind=compaction` clips at 80
 *    chars). Drift to "truncate row's summaryText" would
 *    silently regress this select to no-better-than-parts.
 *
 * 2. **Always walks `includeCompacted=true`** regardless
 *    of input flag. Per kdoc: "Compaction parts are
 *    `includeCompacted=true` only by definition — they're
 *    meta about compaction itself." Drift to "honour
 *    input.includeCompacted" would silently lose
 *    compaction history when caller sends false (e.g.
 *    accidentally).
 *
 * 3. **Body preview clips at 3 rows + 40-char summary
 *    truncation** (NOT the 5-rows + 24-char convention
 *    used by ForksQuery/AncestorsQuery/SessionsQuery/
 *    PartsQuery). The longer-and-fewer-rows preview
 *    surfaces compaction summaries usefully without
 *    blowing the LLM's context.
 */
class CompactionsQueryTest {

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
        title: String = "session-$sid",
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
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

    private suspend fun seedCompaction(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        partId: String,
        fromMid: String,
        toMid: String,
        summary: String,
        createdAtEpochMs: Long,
        compactedAt: Instant? = null,
    ) {
        store.upsertPart(
            Part.Compaction(
                id = PartId(partId),
                messageId = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                compactedAt = compactedAt,
                replacedFromMessageId = MessageId(fromMid),
                replacedToMessageId = MessageId(toMid),
                summary = summary,
            ),
        )
    }

    private fun input(
        sessionId: String?,
        includeCompacted: Boolean? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_COMPACTIONS,
        sessionId = sessionId,
        includeCompacted = includeCompacted,
    )

    private fun rowFields(
        rows: kotlinx.serialization.json.JsonArray,
        key: String,
    ): List<String> = rows.map { row ->
        row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
    }

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runCompactionsQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue("requires sessionId" in (ex.message ?: ""))
    }

    @Test fun missingSessionThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runCompactionsQuery(store, input("ghost"), limit = 100, offset = 0)
        }
        assertTrue("not found" in (ex.message ?: ""))
    }

    // ── Empty result ─────────────────────────────────────────

    @Test fun sessionWithNoCompactionsReturnsEmptyWithFriendlyBody() = runTest {
        val store = newStore()
        seedSession(store, "s1", title = "Some Title")

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(0, result.data.rows.size)
        // Friendly body cites session and "not been compacted yet."
        assertTrue(
            "has not been compacted yet" in result.outputForLlm,
            "body cites no-compactions; got: ${result.outputForLlm}",
        )
        assertTrue("Some Title" in result.outputForLlm, "body cites title")
    }

    // ── summaryText: FULL, not truncated (marquee pin) ──────

    @Test fun rowSummaryTextIsFullNotTruncatedAtEightyChars() = runTest {
        // The marquee differentiator pin: per kdoc "Full
        // summary produced by the compactor — not
        // truncated, unlike `select=parts` preview." Drift
        // to "truncate row's summaryText" would silently
        // regress this select to no-better-than-parts.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        // 200-char summary — well over the 80-char preview
        // limit applied by `select=parts`.
        val longSummary = "A".repeat(200)
        seedCompaction(
            store, "s1", "m1", partId = "p1",
            fromMid = "u-m1", toMid = "m1",
            summary = longSummary,
            createdAtEpochMs = 100L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        // Row's summaryText is the full 200-char string.
        val rowJson = result.data.rows[0].toString()
        assertTrue(
            "\"summaryText\":\"${longSummary}\"" in rowJson,
            "summaryText fully preserved (NOT truncated to 80); got json length: ${rowJson.length}",
        )
    }

    // ── includeCompacted is forced to true (marquee pin) ────

    @Test fun queryAlwaysIncludesCompactedRegardlessOfInputFlag() = runTest {
        // Marquee always-true pin: per kdoc "Compaction
        // parts are includeCompacted=true only by definition."
        // The query MUST surface compaction parts even if
        // the agent passes includeCompacted=false (which
        // would otherwise hide compacted parts in
        // `select=parts`).
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        // Plant a compaction with compactedAt set so it's
        // marked as already-compacted (visibility filter
        // would normally hide it).
        seedCompaction(
            store, "s1", "m1", partId = "p1",
            fromMid = "u-m1", toMid = "m1",
            summary = "first compaction pass",
            createdAtEpochMs = 100L,
            compactedAt = baseTime,
        )

        // Even with explicit includeCompacted=false, this
        // query surfaces the compaction.
        val resultFalse = runCompactionsQuery(
            store,
            input("s1", includeCompacted = false),
            limit = 100,
            offset = 0,
        )
        assertEquals(
            1,
            resultFalse.data.total,
            "compaction surfaces despite includeCompacted=false (handler forces true)",
        )

        // And same with null:
        val resultNull = runCompactionsQuery(
            store,
            input("s1", includeCompacted = null),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, resultNull.data.total)
    }

    // ── Filter to Part.Compaction only ───────────────────────

    @Test fun nonCompactionPartsAreExcluded() = runTest {
        // Pin: filterIsInstance<Part.Compaction>() drops
        // text/tool/etc. parts. Drift to "include all kinds"
        // would surface unrelated parts in compactions
        // results.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")

        // Plant text + reasoning + compaction parts.
        store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = baseTime,
                text = "hello",
            ),
        )
        store.upsertPart(
            Part.Reasoning(
                id = PartId("p-reason"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = baseTime,
                text = "thinking",
            ),
        )
        seedCompaction(
            store, "s1", "m1", partId = "p-comp",
            fromMid = "u-m1", toMid = "m1",
            summary = "summary",
            createdAtEpochMs = 100L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(1, result.data.total, "only Part.Compaction counted")
        assertEquals(listOf("p-comp"), rowFields(result.data.rows, "partId"))
    }

    // ── Sort: createdAt DESC ─────────────────────────────────

    @Test fun sortIsCreatedAtDescendingMostRecentFirst() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedCompaction(
            store, "s1", "m1", partId = "old",
            fromMid = "u-m1", toMid = "m1",
            summary = "first pass", createdAtEpochMs = 100L,
        )
        seedCompaction(
            store, "s1", "m1", partId = "newest",
            fromMid = "u-m1", toMid = "m1",
            summary = "third pass", createdAtEpochMs = 300L,
        )
        seedCompaction(
            store, "s1", "m1", partId = "mid",
            fromMid = "u-m1", toMid = "m1",
            summary = "second pass", createdAtEpochMs = 200L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        assertContentEquals(
            listOf("newest", "mid", "old"),
            rowFields(result.data.rows, "partId"),
            "createdAt DESC most-recent first",
        )
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRows() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedCompaction(
                store, "s1", "m1", partId = "c-$i",
                fromMid = "u-m1", toMid = "m1",
                summary = "summary-$i",
                createdAtEpochMs = i.toLong(),
            )
        }

        val result = runCompactionsQuery(store, input("s1"), limit = 2, offset = 0)
        assertEquals(5, result.data.total, "total reports unfiltered")
        assertEquals(2, result.data.returned)
        // Most-recent 2: c-5, c-4.
        assertContentEquals(
            listOf("c-5", "c-4"),
            rowFields(result.data.rows, "partId"),
        )
    }

    @Test fun paginationOffsetSkipsAfterSort() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedCompaction(
                store, "s1", "m1", partId = "c-$i",
                fromMid = "u-m1", toMid = "m1",
                summary = "summary-$i",
                createdAtEpochMs = i.toLong(),
            )
        }

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 2)
        assertEquals(5, result.data.total)
        assertEquals(3, result.data.returned)
        assertContentEquals(
            listOf("c-3", "c-2", "c-1"),
            rowFields(result.data.rows, "partId"),
        )
    }

    // ── CompactionRow shape ──────────────────────────────────

    @Test fun rowExposesAllPartCompactionFields() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.upsertPart(
            Part.Compaction(
                id = PartId("p1"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = createdAt,
                replacedFromMessageId = MessageId("from-msg"),
                replacedToMessageId = MessageId("to-msg"),
                summary = "the summary text",
            ),
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"partId\":\"p1\"" in rowJson)
        assertTrue("\"messageId\":\"m1\"" in rowJson)
        assertTrue("\"fromMessageId\":\"from-msg\"" in rowJson)
        assertTrue("\"toMessageId\":\"to-msg\"" in rowJson)
        assertTrue("\"summaryText\":\"the summary text\"" in rowJson)
        assertTrue(
            "\"compactedAtEpochMs\":${createdAt.toEpochMilliseconds()}" in rowJson,
            "compactedAtEpochMs from createdAt; got: $rowJson",
        )
    }

    // ── Body preview format ─────────────────────────────────

    @Test fun bodyPreviewClipsAtThreeRowsNotFive() = runTest {
        // Marquee 3-not-5 pin: this query's body shows 3
        // rows where ForksQuery/AncestorsQuery/SessionsQuery/
        // PartsQuery show 5. The trade-off is fewer rows
        // but each row's summary preview is longer.
        // Drift to "5 rows like other queries" would
        // crowd the LLM's context.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedCompaction(
                store, "s1", "m1", partId = "c-$i",
                fromMid = "u-m1", toMid = "m1",
                summary = "compaction $i",
                createdAtEpochMs = i.toLong(),
            )
        }

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        // Body has c-5, c-4, c-3 (top 3) + truncation suffix.
        assertTrue("compaction 5" in result.outputForLlm)
        assertTrue("compaction 4" in result.outputForLlm)
        assertTrue("compaction 3" in result.outputForLlm)
        // c-2 and c-1 NOT in body (truncated past 3rd).
        assertTrue(
            "compaction 2" !in result.outputForLlm,
            "4th row excluded from body; got: ${result.outputForLlm}",
        )
        assertTrue(
            result.outputForLlm.endsWith("; …"),
            "truncation suffix; got: ${result.outputForLlm}",
        )
    }

    @Test fun bodySummaryTruncatesAtFortyCharsWithEllipsis() = runTest {
        // Pin: body shows summary truncated to 40 chars
        // with "…" suffix when longer. Drift to 24 chars
        // (the parts-preview convention) would lose context;
        // drift to 80+ would inflate.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val longSummary = "B".repeat(80)
        seedCompaction(
            store, "s1", "m1", partId = "p1",
            fromMid = "u-m1", toMid = "m1",
            summary = longSummary,
            createdAtEpochMs = 100L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        // Body has 40 B's + "…".
        val expectedPrefix = "B".repeat(40) + "…"
        assertTrue(
            expectedPrefix in result.outputForLlm,
            "body summary clipped at 40 + '…'; got: ${result.outputForLlm}",
        )
        // 41 B's should NOT appear.
        assertTrue(
            "B".repeat(41) !in result.outputForLlm,
            "body summary does NOT extend past 40 chars",
        )
        // BUT the row's full summaryText still has all 80
        // (per the marquee pin above — verified in
        // rowSummaryTextIsFullNotTruncatedAtEightyChars).
    }

    @Test fun bodyShortSummaryNoEllipsisWhenUnderForty() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedCompaction(
            store, "s1", "m1", partId = "p1",
            fromMid = "u-m1", toMid = "m1",
            summary = "short summary",
            createdAtEpochMs = 100L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        // Body has "short summary" without trailing "…".
        assertTrue("short summary" in result.outputForLlm)
        // Body has "short summary)" — i.e. the close-paren
        // immediately after, NO ellipsis-then-paren.
        assertTrue(
            "short summary)" in result.outputForLlm,
            "no ellipsis when summary < 40 chars; got: ${result.outputForLlm}",
        )
    }

    @Test fun bodyCitesFromArrowToMessageIds() = runTest {
        // Pin: body's per-row preview format is "from→to
        // (summary)". Drift to "to→from" or different
        // arrow would break agent's "what range was
        // compacted" reading.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedCompaction(
            store, "s1", "m1", partId = "p1",
            fromMid = "msg-from", toMid = "msg-to",
            summary = "x", createdAtEpochMs = 100L,
        )

        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        assertTrue(
            "msg-from→msg-to" in result.outputForLlm,
            "from→to format; got: ${result.outputForLlm}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsCompactions() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        val result = runCompactionsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_COMPACTIONS, result.data.select)
    }

    @Test fun toolResultTitleCitesReturnedAndTotal() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..3) {
            seedCompaction(
                store, "s1", "m1", partId = "c-$i",
                fromMid = "u-m1", toMid = "m1",
                summary = "x", createdAtEpochMs = i.toLong(),
            )
        }

        val result = runCompactionsQuery(store, input("s1"), limit = 2, offset = 0)
        assertTrue(
            "(2/3)" in result.title!!,
            "(returned/total); got: ${result.title}",
        )
    }
}
