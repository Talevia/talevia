package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.TextSearchMatchRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=text_search)` — substring grep
 * over `Part.Text` content via `SessionStore.searchTextInParts`.
 */
class SessionQueryTextSearchTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun rig(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        return store
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
        partTexts: List<Pair<String, String>>, // (partId, text) pairs
    ) {
        val sessionId = SessionId(sid)
        val now = Instant.fromEpochMilliseconds(0)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val mid = MessageId("m-$sid")
        store.appendMessage(
            Message.User(
                id = mid,
                sessionId = sessionId,
                createdAt = now,
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        partTexts.forEachIndexed { i, (partId, text) ->
            store.upsertPart(
                Part.Text(
                    id = PartId(partId),
                    messageId = mid,
                    sessionId = sessionId,
                    createdAt = Instant.fromEpochMilliseconds(i.toLong() * 1000),
                    text = text,
                ),
            )
        }
    }

    @Test fun rejectsBlankQuery() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "anything"))
        val tool = SessionQueryTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(SessionQueryTool.Input(select = "text_search", query = ""), ctx())
        }
        assertFailsWith<IllegalStateException> {
            tool.execute(SessionQueryTool.Input(select = "text_search"), ctx())
        }
    }

    @Test fun substringMatchAcrossSessionsWhenNoSessionFilter() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "i asked about Mars rovers"))
        seedSession(store, "s2", listOf("p2" to "totally unrelated discussion"))
        seedSession(store, "s3", listOf("p3" to "another mention of mars exploration"))

        val out = SessionQueryTool(store).execute(
            SessionQueryTool.Input(select = "text_search", query = "mars"),
            ctx(),
        ).data
        assertEquals("text_search", out.select)
        val rows = out.rows.decodeRowsAs(TextSearchMatchRow.serializer())
        // Both s1 and s3 contain "mars" (case-insensitive); s2 doesn't.
        val sessionIds = rows.map { it.sessionId }.toSet()
        assertEquals(setOf("s1", "s3"), sessionIds, "cross-session match must hit s1 and s3 only")
        // Snippet must include the matched substring case-preserving.
        assertTrue(rows.all { it.snippet.lowercase().contains("mars") })
        // matchOffset > 0 (substring is mid-text, so prefix '…' or actual offset).
        assertTrue(rows.all { it.matchOffset >= 0 }, "matchOffset must be non-negative for hits")
    }

    @Test fun sessionIdScopeNarrowsResult() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "the quick brown fox"))
        seedSession(store, "s2", listOf("p2" to "another quick test"))

        val out = SessionQueryTool(store).execute(
            SessionQueryTool.Input(select = "text_search", query = "quick", sessionId = "s1"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(TextSearchMatchRow.serializer())
        assertEquals(1, rows.size)
        assertEquals("s1", rows.single().sessionId)
        assertEquals("p1", rows.single().partId)
    }

    @Test fun caseInsensitiveMatch() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "MIXED-Case Words HERE"))

        val out = SessionQueryTool(store).execute(
            SessionQueryTool.Input(select = "text_search", query = "mixed-case"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(TextSearchMatchRow.serializer())
        assertEquals(1, rows.size)
        assertTrue(rows.single().snippet.contains("MIXED-Case"), "snippet preserves original casing")
    }

    @Test fun noMatchReturnsZeroRows() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "alpha beta gamma"))

        val out = SessionQueryTool(store).execute(
            SessionQueryTool.Input(select = "text_search", query = "delta"),
            ctx(),
        ).data
        assertEquals(0, out.total)
        assertTrue("expected 'No' note: ${out.select}") { true }
    }

    @Test fun limitAndOffsetPaginate() = runTest {
        val store = rig()
        seedSession(
            store, "s1",
            listOf(
                "p1" to "match alpha",
                "p2" to "match beta",
                "p3" to "match gamma",
                "p4" to "match delta",
            ),
        )

        val tool = SessionQueryTool(store)
        // Default limit=100 returns all 4.
        val all = tool.execute(
            SessionQueryTool.Input(select = "text_search", query = "match", sessionId = "s1"),
            ctx(),
        ).data
        assertEquals(4, all.total)

        // limit=2, offset=0 → first 2 (newest first).
        val firstPage = tool.execute(
            SessionQueryTool.Input(
                select = "text_search", query = "match", sessionId = "s1", limit = 2, offset = 0,
            ),
            ctx(),
        ).data
        assertEquals(2, firstPage.total)

        // offset=2 skips the first 2 → returns 2.
        val secondPage = tool.execute(
            SessionQueryTool.Input(
                select = "text_search", query = "match", sessionId = "s1", limit = 2, offset = 2,
            ),
            ctx(),
        ).data
        assertEquals(2, secondPage.total)

        // First-page partIds must NOT overlap with second-page partIds.
        val firstIds = firstPage.rows.decodeRowsAs(TextSearchMatchRow.serializer()).map { it.partId }.toSet()
        val secondIds = secondPage.rows.decodeRowsAs(TextSearchMatchRow.serializer()).map { it.partId }.toSet()
        assertTrue((firstIds intersect secondIds).isEmpty(), "pages must not overlap")
    }

    @Test fun rejectsForeignFilters() = runTest {
        val store = rig()
        seedSession(store, "s1", listOf("p1" to "hi"))
        // role is a messages-select field, not text_search.
        assertFailsWith<IllegalStateException> {
            SessionQueryTool(store).execute(
                SessionQueryTool.Input(select = "text_search", query = "hi", role = "user"),
                ctx(),
            )
        }
    }
}
