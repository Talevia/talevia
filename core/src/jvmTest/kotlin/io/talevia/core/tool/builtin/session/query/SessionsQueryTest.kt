package io.talevia.core.tool.builtin.session.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [runSessionsQuery] —
 * `core/tool/builtin/session/query/SessionsQuery.kt`. The
 * `select=sessions` handler returning all sessions, most-
 * recent first by `updatedAt`. Sister to cycles 180/181's
 * ForksQuery + AncestorsQuery. Cycle 182 audit: 71 LOC, 0
 * direct test refs (used through full-tool integration
 * but the projectId-filter / includeArchived-filter / sort
 * order / scope-label-format contracts were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Sort order is `updatedAt` DESCENDING (most-recent
 *    first).** Drift to ascending or createdAt-based sort
 *    would silently reorder every session list visible to
 *    the agent / UI.
 *
 * 2. **`projectId` filter: null/blank → all projects;
 *    non-blank → filter to that project.** Drift to
 *    "blank treated as a literal projectId" would fail
 *    every "show me all sessions" query when the agent
 *    accidentally sends an empty string.
 *
 * 3. **`includeArchived` filter: null → false (DEFAULT
 *    EXCLUDE archived); true → include.** Drift to "null
 *    means include" would surface archived sessions that
 *    the user explicitly hid.
 */
class SessionsQueryTest {

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
        projectId: String = "p",
        title: String = "session-$sid",
        parentId: SessionId? = null,
        archived: Boolean = false,
        updatedAt: Instant = baseTime,
        createdAt: Instant = baseTime,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId(projectId),
                title = title,
                parentId = parentId,
                createdAt = createdAt,
                updatedAt = updatedAt,
                archived = archived,
            ),
        )
        return sessionId
    }

    private fun input(
        projectId: String? = null,
        includeArchived: Boolean? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_SESSIONS,
        projectId = projectId,
        includeArchived = includeArchived,
    )

    private fun rowIds(rows: kotlinx.serialization.json.JsonArray): List<String> =
        rows.map { row ->
            row.toString().substringAfter("\"id\":\"").substringBefore("\"")
        }

    // ── Empty store ──────────────────────────────────────────

    @Test fun emptyStoreReturnsZeroRowsAndAllProjectsScope() = runTest {
        val store = newStore()
        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(
            "No sessions on all projects" in result.outputForLlm,
            "outputForLlm cites 'all projects' scope; got: ${result.outputForLlm}",
        )
    }

    // ── projectId filter ─────────────────────────────────────

    @Test fun nullProjectIdReturnsAllSessions() = runTest {
        val store = newStore()
        seedSession(store, "s1", projectId = "p-a")
        seedSession(store, "s2", projectId = "p-b")

        val result = runSessionsQuery(store, input(projectId = null), limit = 100, offset = 0)
        assertEquals(2, result.data.total, "all projects' sessions returned")
    }

    @Test fun blankProjectIdTreatedAsNullAllProjects() = runTest {
        // Marquee blank-projectId pin: per
        // `input.projectId?.takeIf { it.isNotBlank() }`,
        // an empty / whitespace projectId resolves to
        // `null` → all-projects. Drift to "blank treated
        // as a literal projectId" would fail every "show
        // me all sessions" query when the agent
        // accidentally sends "".
        val store = newStore()
        seedSession(store, "s1", projectId = "p-a")
        seedSession(store, "s2", projectId = "p-b")

        val resultEmpty = runSessionsQuery(
            store,
            input(projectId = ""),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, resultEmpty.data.total, "empty projectId → all projects")

        val resultBlank = runSessionsQuery(
            store,
            input(projectId = "   "),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, resultBlank.data.total, "whitespace projectId → all projects")
    }

    @Test fun nonBlankProjectIdFiltersToThatProject() = runTest {
        val store = newStore()
        seedSession(store, "s1", projectId = "p-a")
        seedSession(store, "s2", projectId = "p-b")
        seedSession(store, "s3", projectId = "p-a")

        val result = runSessionsQuery(
            store,
            input(projectId = "p-a"),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, result.data.total, "only p-a sessions")
        // outputForLlm cites the specific project scope.
        assertTrue(
            "project p-a" in result.outputForLlm,
            "scope label cites projectId; got: ${result.outputForLlm}",
        )
    }

    // ── includeArchived filter ──────────────────────────────

    @Test fun includeArchivedNullDefaultsToFalseExcludeArchived() = runTest {
        // Marquee default-exclude pin: per
        // `input.includeArchived ?: false`, the default
        // hides archived sessions. Drift to "null means
        // include" would silently surface sessions the
        // user explicitly hid.
        val store = newStore()
        seedSession(store, "active")
        seedSession(store, "archived-1", archived = true)
        seedSession(store, "archived-2", archived = true)

        val result = runSessionsQuery(
            store,
            input(includeArchived = null),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, result.data.total, "default null excludes archived")
        assertEquals(listOf("active"), rowIds(result.data.rows))
    }

    @Test fun includeArchivedFalseExcludesArchived() = runTest {
        val store = newStore()
        seedSession(store, "active")
        seedSession(store, "archived", archived = true)

        val result = runSessionsQuery(
            store,
            input(includeArchived = false),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, result.data.total)
        assertEquals(listOf("active"), rowIds(result.data.rows))
    }

    @Test fun includeArchivedTrueIncludesArchived() = runTest {
        val store = newStore()
        seedSession(store, "active")
        seedSession(store, "archived", archived = true)

        val result = runSessionsQuery(
            store,
            input(includeArchived = true),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, result.data.total, "archived included")
    }

    // ── Sort order: updatedAt DESC ──────────────────────────

    @Test fun sortIsUpdatedAtDescendingMostRecentFirst() = runTest {
        // Marquee sort-order pin: most-recent first.
        // Drift to ascending or createdAt-based would
        // silently reorder every session list.
        val store = newStore()
        // Distinct updatedAt timestamps; descending
        // expected order is newest, mid, oldest.
        seedSession(
            store,
            "oldest",
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
        )
        seedSession(
            store,
            "newest",
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_003_000L),
        )
        seedSession(
            store,
            "mid",
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_002_000L),
        )

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertContentEquals(
            listOf("newest", "mid", "oldest"),
            rowIds(result.data.rows),
            "most-recent first",
        )
    }

    @Test fun sortIsByUpdatedAtNotByCreatedAt() = runTest {
        // Pin: createdAt is irrelevant for ordering. A
        // session with old createdAt + recent updatedAt
        // sorts AHEAD of a session with new createdAt +
        // older updatedAt.
        val store = newStore()
        seedSession(
            store,
            "old-created-recent-updated",
            createdAt = Instant.fromEpochMilliseconds(1_000L),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_002_000L),
        )
        seedSession(
            store,
            "new-created-old-updated",
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
        )

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertEquals(
            "old-created-recent-updated",
            rowIds(result.data.rows).first(),
            "first row is most-recently-updated regardless of createdAt",
        )
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsPostSort() = runTest {
        val store = newStore()
        for (i in 1..5) {
            seedSession(
                store,
                "s-$i",
                updatedAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runSessionsQuery(store, input(), limit = 2, offset = 0)
        assertEquals(5, result.data.total, "total reports unfiltered count")
        assertEquals(2, result.data.returned, "returned = limit")
        // First 2 are the 2 most recent: s-5, s-4.
        assertContentEquals(listOf("s-5", "s-4"), rowIds(result.data.rows))
    }

    @Test fun paginationOffsetSkipsAfterSort() = runTest {
        val store = newStore()
        for (i in 1..5) {
            seedSession(
                store,
                "s-$i",
                updatedAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runSessionsQuery(store, input(), limit = 100, offset = 2)
        assertEquals(5, result.data.total)
        assertEquals(3, result.data.returned, "5 - 2 offset = 3 returned")
        // Skip the 2 most-recent (s-5, s-4); next 3 are
        // s-3, s-2, s-1.
        assertContentEquals(
            listOf("s-3", "s-2", "s-1"),
            rowIds(result.data.rows),
        )
    }

    // ── SessionRow shape ─────────────────────────────────────

    @Test fun sessionRowExposesAllSessionFields() = runTest {
        val store = newStore()
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val updatedAt = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        seedSession(
            store,
            "parent",
        )
        seedSession(
            store,
            "child",
            title = "Child Session",
            projectId = "p-x",
            parentId = SessionId("parent"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            archived = true,
        )

        val result = runSessionsQuery(
            store,
            input(includeArchived = true),
            limit = 100,
            offset = 0,
        )
        val childRow = result.data.rows
            .map { it.toString() }
            .first { "\"id\":\"child\"" in it }

        assertTrue("\"projectId\":\"p-x\"" in childRow, "projectId; got: $childRow")
        assertTrue("\"title\":\"Child Session\"" in childRow, "title; got: $childRow")
        assertTrue("\"parentId\":\"parent\"" in childRow, "parentId; got: $childRow")
        assertTrue(
            "\"createdAtEpochMs\":${createdAt.toEpochMilliseconds()}" in childRow,
            "createdAtEpochMs; got: $childRow",
        )
        assertTrue(
            "\"updatedAtEpochMs\":${updatedAt.toEpochMilliseconds()}" in childRow,
            "updatedAtEpochMs; got: $childRow",
        )
        assertTrue("\"archived\":true" in childRow, "archived; got: $childRow")
    }

    @Test fun rootSessionRowHasNullParentId() = runTest {
        val store = newStore()
        seedSession(store, "root", parentId = null)

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        // parentId should be null or absent (depends on
        // serializer's handling of nullable defaults).
        assertTrue(
            "\"parentId\":null" in rowJson || "\"parentId\":" !in rowJson,
            "parentId surfaces as null or absent for root; got: $rowJson",
        )
    }

    // ── outputForLlm format conventions ──────────────────────

    @Test fun outputForLlmCitesProjectScope() = runTest {
        val store = newStore()
        seedSession(store, "s1", projectId = "p-a")

        val resultProj = runSessionsQuery(
            store,
            input(projectId = "p-a"),
            limit = 100,
            offset = 0,
        )
        assertTrue("project p-a" in resultProj.outputForLlm)

        val resultAll = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertTrue("all projects" in resultAll.outputForLlm)
    }

    @Test fun outputForLlmCitesCountAndIds() = runTest {
        val store = newStore()
        seedSession(store, "s1", title = "First")
        seedSession(store, "s2", title = "Second")

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertTrue("2 session(s)" in result.outputForLlm, "count format")
        assertTrue("s1 'First'" in result.outputForLlm)
        assertTrue("s2 'Second'" in result.outputForLlm)
    }

    @Test fun outputForLlmTruncatesAfterFiveRowsWithEllipsis() = runTest {
        val store = newStore()
        for (i in 1..7) {
            seedSession(
                store,
                "s-$i",
                updatedAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertEquals(7, result.data.total)
        assertTrue(result.outputForLlm.endsWith("; …"), "truncation suffix; got: ${result.outputForLlm}")
    }

    @Test fun outputForLlmNoEllipsisAtExactlyFiveRows() = runTest {
        val store = newStore()
        for (i in 1..5) {
            seedSession(
                store,
                "s-$i",
                updatedAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertTrue(
            !result.outputForLlm.endsWith("; …"),
            "no ellipsis at exactly 5; got: ${result.outputForLlm}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsSessions() = runTest {
        val store = newStore()
        val result = runSessionsQuery(store, input(), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_SESSIONS, result.data.select)
    }

    @Test fun toolResultTitleCitesReturnedAndTotal() = runTest {
        val store = newStore()
        seedSession(store, "s1")
        seedSession(store, "s2")

        val result = runSessionsQuery(store, input(), limit = 1, offset = 0)
        assertTrue(
            "(1/2)" in result.title!!,
            "(returned/total) format; got: ${result.title}",
        )
    }
}
