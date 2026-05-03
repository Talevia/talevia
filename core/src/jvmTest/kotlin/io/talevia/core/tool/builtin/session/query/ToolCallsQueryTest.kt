package io.talevia.core.tool.builtin.session.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
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
import io.talevia.core.session.ToolState
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runToolCallsQuery] —
 * `core/tool/builtin/session/query/ToolCallsQuery.kt`. The
 * SessionQueryTool's `select=tool_calls` handler — completes
 * the SessionQueryTool main-handler audit started in cycles
 * 180-183 and continued in cycle 188 (CompactionsQuery). Cycle
 * 189 audit: 81 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`ToolState.Failed → "error"` (surprising rename) and
 *    other state mappings.** The kdoc literal: `"pending" |
 *    "running" | "completed" | "error"`. The `Failed` →
 *    `"error"` rename is a documented departure from the
 *    sealed-type's name. Pinned for all 5 ToolState
 *    variants (also Cancelled → "cancelled" which IS in the
 *    impl but not in the kdoc — drift between kdoc and
 *    impl).
 *
 * 2. **`toolId` filter: null/blank → all; non-blank → exact
 *    match.** Drift to "substring" or "case-insensitive"
 *    would silently change which rows surface.
 *
 * 3. **`includeCompacted` defaults to TRUE** (matches
 *    PartsQuery's default; opposite of SessionsQuery's
 *    `includeArchived` default-false). Per kdoc: tool_calls
 *    are agent decision-history; compacted entries are
 *    still load-bearing context for replay/audit.
 */
class ToolCallsQueryTest {

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

    private suspend fun seedToolPart(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        partId: String,
        toolId: String,
        state: ToolState,
        createdAtEpochMs: Long,
        title: String? = null,
        compactedAt: Instant? = null,
    ) {
        store.upsertPart(
            Part.Tool(
                id = PartId(partId),
                messageId = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                compactedAt = compactedAt,
                callId = CallId("call-$partId"),
                toolId = toolId,
                state = state,
                title = title,
            ),
        )
    }

    private fun input(
        sessionId: String?,
        toolId: String? = null,
        includeCompacted: Boolean? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_TOOL_CALLS,
        sessionId = sessionId,
        toolId = toolId,
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
            runToolCallsQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue("requires sessionId" in (ex.message ?: ""))
    }

    // ── State mapping (5 ToolState variants) ────────────────

    @Test fun pendingToolStateMapsToPendingString() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(listOf("pending"), rowFields(result.data.rows, "state"))
    }

    @Test fun runningToolStateMapsToRunningString() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p", toolId = "x",
            state = ToolState.Running(JsonNull),
            createdAtEpochMs = 100L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(listOf("running"), rowFields(result.data.rows, "state"))
    }

    @Test fun completedToolStateMapsToCompletedString() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p", toolId = "x",
            state = ToolState.Completed(
                input = JsonNull,
                outputForLlm = "ok",
                data = JsonObject(emptyMap()),
            ),
            createdAtEpochMs = 100L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(listOf("completed"), rowFields(result.data.rows, "state"))
    }

    @Test fun failedToolStateMapsToErrorStringNotFailed() = runTest {
        // Marquee surprising-rename pin: per kdoc literal:
        // `"pending" | "running" | "completed" | "error"`.
        // The `Failed → "error"` mapping is the rename
        // documented at cycle 153's ToolCallTreeTest finding
        // — drift to "failed" (matching the sealed-type's
        // name) would change every consumer's state-string
        // matching.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p", toolId = "x",
            state = ToolState.Failed(input = JsonNull, message = "oops"),
            createdAtEpochMs = 100L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(
            listOf("error"),
            rowFields(result.data.rows, "state"),
            "Failed → 'error' (surprising rename); drift to 'failed' would change consumer matching",
        )
    }

    @Test fun cancelledToolStateMapsToCancelledString() = runTest {
        // Pin: ToolState.Cancelled → "cancelled". The kdoc
        // literal is missing "cancelled" in the documented
        // string set ("pending|running|completed|error"),
        // but the impl does map it. Pinning actual behavior;
        // a conservative reading of the kdoc would suggest
        // the kdoc just predates the Cancelled variant and
        // was never refreshed.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p", toolId = "x",
            state = ToolState.Cancelled(input = JsonNull, message = "user cancelled"),
            createdAtEpochMs = 100L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(listOf("cancelled"), rowFields(result.data.rows, "state"))
    }

    // ── toolId filter ────────────────────────────────────────

    @Test fun nullToolIdReturnsAllToolCalls() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p1", toolId = "echo",
            state = ToolState.Completed(
                input = JsonNull, outputForLlm = "x", data = JsonObject(emptyMap()),
            ),
            createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "p2", toolId = "list_tools",
            state = ToolState.Completed(
                input = JsonNull, outputForLlm = "x", data = JsonObject(emptyMap()),
            ),
            createdAtEpochMs = 50L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(2, result.data.total)
    }

    @Test fun blankToolIdIsTreatedAsNoFilter() = runTest {
        // Pin: per `input.toolId.isNullOrBlank()`, empty/
        // whitespace toolId resolves to no-filter.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p1", toolId = "echo",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )

        val resultEmpty = runToolCallsQuery(
            store, input("s1", toolId = ""),
            limit = 100, offset = 0,
        )
        assertEquals(1, resultEmpty.data.total, "empty toolId is no-filter")

        val resultBlank = runToolCallsQuery(
            store, input("s1", toolId = "   "),
            limit = 100, offset = 0,
        )
        assertEquals(1, resultBlank.data.total, "whitespace toolId is no-filter")
    }

    @Test fun nonBlankToolIdIsExactMatchNotSubstring() = runTest {
        // Marquee exact-match pin: per impl `it.toolId ==
        // input.toolId`. Drift to "startsWith" or
        // "contains" would silently surface unrelated tools.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p1", toolId = "echo",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "p2", toolId = "echo_v2",
            state = ToolState.Pending, createdAtEpochMs = 50L,
        )

        // toolId="echo" matches ONLY exact "echo", NOT "echo_v2".
        val result = runToolCallsQuery(
            store, input("s1", toolId = "echo"),
            limit = 100, offset = 0,
        )
        assertEquals(1, result.data.total, "exact match excludes 'echo_v2'")
        assertEquals(listOf("p1"), rowFields(result.data.rows, "partId"))
    }

    // ── includeCompacted defaults to TRUE ────────────────────

    @Test fun includeCompactedNullDefaultsToTrue() = runTest {
        // Marquee default-true pin: matches PartsQuery's
        // default-true (cycle 183), opposite of
        // SessionsQuery's includeArchived-default-false.
        // Tool calls are agent decision-history — compacted
        // entries still load-bearing for replay/audit.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p-active", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "p-compacted", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 50L,
            compactedAt = baseTime,
        )

        val result = runToolCallsQuery(
            store, input("s1", includeCompacted = null),
            limit = 100, offset = 0,
        )
        assertEquals(2, result.data.total, "null defaults to true (BOTH parts surface)")
    }

    @Test fun includeCompactedFalseExcludesCompacted() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p-active", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "p-compacted", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 50L,
            compactedAt = baseTime,
        )

        val result = runToolCallsQuery(
            store, input("s1", includeCompacted = false),
            limit = 100, offset = 0,
        )
        assertEquals(1, result.data.total, "false excludes compacted")
        assertEquals(listOf("p-active"), rowFields(result.data.rows, "partId"))
    }

    // ── Filter to Part.Tool only ─────────────────────────────

    @Test fun nonToolPartsAreExcluded() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p-tool", toolId = "echo",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = baseTime,
                text = "hello",
            ),
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(1, result.data.total)
        assertEquals(listOf("p-tool"), rowFields(result.data.rows, "partId"))
    }

    // ── Sort: createdAt DESC ─────────────────────────────────

    @Test fun sortIsCreatedAtDescendingMostRecentFirst() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "old", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "newest", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 300L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "mid", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 200L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertContentEquals(
            listOf("newest", "mid", "old"),
            rowFields(result.data.rows, "partId"),
        )
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsTotalReportsFiltered() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..5) {
            seedToolPart(
                store, "s1", "m1", partId = "p-$i", toolId = "x",
                state = ToolState.Pending, createdAtEpochMs = i.toLong(),
            )
        }

        val result = runToolCallsQuery(store, input("s1"), limit = 2, offset = 0)
        assertEquals(5, result.data.total)
        assertEquals(2, result.data.returned)
        // Most-recent 2: p-5, p-4.
        assertContentEquals(listOf("p-5", "p-4"), rowFields(result.data.rows, "partId"))
    }

    // ── ToolCallRow shape ────────────────────────────────────

    @Test fun toolCallRowExposesAllDocumentedFields() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.upsertPart(
            Part.Tool(
                id = PartId("p1"),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = createdAt,
                callId = CallId("call-xyz"),
                toolId = "echo",
                state = ToolState.Completed(
                    input = JsonNull, outputForLlm = "ok", data = JsonObject(emptyMap()),
                ),
                title = "echo Hello",
            ),
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"partId\":\"p1\"" in rowJson, "partId; got: $rowJson")
        assertTrue("\"messageId\":\"m1\"" in rowJson, "messageId")
        assertTrue("\"toolId\":\"echo\"" in rowJson, "toolId")
        assertTrue("\"callId\":\"call-xyz\"" in rowJson, "callId")
        assertTrue("\"state\":\"completed\"" in rowJson, "state")
        assertTrue("\"title\":\"echo Hello\"" in rowJson, "title")
        assertTrue(
            "\"createdAtEpochMs\":${createdAt.toEpochMilliseconds()}" in rowJson,
        )
    }

    @Test fun toolCallRowTitleIsNullableWhenAbsent() = runTest {
        // Pin: title is nullable; absent on the input
        // produces null/absent in JSON.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p1", toolId = "x",
            state = ToolState.Pending, createdAtEpochMs = 100L,
            title = null,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        // title is null or absent.
        assertTrue(
            "\"title\":null" in rowJson || "\"title\":" !in rowJson,
            "title null/absent; got: $rowJson",
        )
    }

    // ── outputForLlm format ─────────────────────────────────

    @Test fun emptyResultBodyOmitsToolIdScopeWhenNoFilter() = runTest {
        val store = newStore()
        seedSession(store, "s1", title = "Some Title")

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        // Body cites session info + "no tool calls." — no toolId scope.
        assertTrue("has no tool calls" in result.outputForLlm, "no-deps phrase")
        assertTrue("Some Title" in result.outputForLlm, "session title cited")
        assertTrue(
            "toolId=" !in result.outputForLlm,
            "no scope citation when filter absent; got: ${result.outputForLlm}",
        )
    }

    @Test fun emptyResultBodyAppendsToolIdScopeWhenFiltering() = runTest {
        val store = newStore()
        seedSession(store, "s1")

        val result = runToolCallsQuery(
            store, input("s1", toolId = "echo"),
            limit = 100, offset = 0,
        )
        assertTrue("has no tool calls toolId=echo" in result.outputForLlm)
    }

    @Test fun nonEmptyBodyCitesCountAndPerRowFormat() = runTest {
        // Pin: per-row format "{toolId}[{state}]" — drift
        // would lose the "tool / state" pairing the agent
        // reads.
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        seedToolPart(
            store, "s1", "m1", partId = "p1", toolId = "echo",
            state = ToolState.Pending, createdAtEpochMs = 100L,
        )
        seedToolPart(
            store, "s1", "m1", partId = "p2", toolId = "list_tools",
            state = ToolState.Failed(input = JsonNull, message = "oops"),
            createdAtEpochMs = 50L,
        )

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertTrue("2 of 2 tool call(s)" in result.outputForLlm)
        assertTrue("most recent first" in result.outputForLlm)
        assertTrue("echo[pending]" in result.outputForLlm)
        assertTrue(
            "list_tools[error]" in result.outputForLlm,
            "Failed → error per-row preview; got: ${result.outputForLlm}",
        )
    }

    @Test fun bodyTruncatesAfterFiveRowsWithEllipsis() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "m1")
        for (i in 1..7) {
            seedToolPart(
                store, "s1", "m1", partId = "p-$i", toolId = "x",
                state = ToolState.Pending, createdAtEpochMs = i.toLong(),
            )
        }

        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(7, result.data.total)
        assertTrue(result.outputForLlm.endsWith("; …"))
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsToolCalls() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        val result = runToolCallsQuery(store, input("s1"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_TOOL_CALLS, result.data.select)
    }
}
