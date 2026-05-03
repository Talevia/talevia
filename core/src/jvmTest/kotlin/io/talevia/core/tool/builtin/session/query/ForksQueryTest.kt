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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runForksQuery] —
 * `core/tool/builtin/session/query/ForksQuery.kt`. The
 * `select=forks` handler returning immediate child
 * sessions of a given session, oldest first. Cycle 180
 * audit: 61 LOC, 0 direct test refs (used through full-
 * tool integration but the branch-level contracts —
 * required-input rejection, missing-parent error, empty-
 * children body, pagination, output-format conventions
 * — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`sessionId` required for `select=forks`; missing
 *    parent throws.** Drift to "silent empty list" would
 *    silently return zero forks for unknown ids,
 *    masking typos.
 *
 * 2. **One-hop only: `listChildSessions(parent.id)` —
 *    grandchildren NOT included.** Per kdoc: "One hop
 *    only — deeper traversal is the caller's job via
 *    repeated queries." Drift to recursive walk would
 *    fold grandchildren in silently and double-count
 *    consumers' UI.
 *
 * 3. **Pagination: `drop(offset).take(limit)`; `total`
 *    reports unfiltered child count, `returned`
 *    reports the page size; outputForLlm cites both.**
 *    Drift to "limit applied to total" would silently
 *    truncate the visible-total signal the agent reads
 *    to know more pages exist.
 *
 * Plus structural: ForkRow shape preserves Session
 * fields verbatim (id, projectId, title, createdAt /
 * updatedAt as epoch ms, archived); empty-children
 * body cites parent's title; truncation suffix `; …`
 * appears when rows.size > 5.
 */
class ForksQueryTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

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
        parentId: SessionId? = null,
        createdAt: Instant = now,
        updatedAt: Instant = now,
        archived: Boolean = false,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                parentId = parentId,
                createdAt = createdAt,
                updatedAt = updatedAt,
                archived = archived,
            ),
        )
        return sessionId
    }

    private fun input(sessionId: String?): SessionQueryTool.Input =
        SessionQueryTool.Input(
            select = SessionQueryTool.SELECT_FORKS,
            sessionId = sessionId,
        )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrows() = runTest {
        // Pin: requireSession's `error("...")` produces an
        // IllegalStateException with the documented hint
        // "Call session_query(select=sessions) to discover
        // valid ids." Drift to silent empty list would
        // mask agent typos.
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runForksQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue(
            "requires sessionId" in (ex.message ?: ""),
            "expected 'requires sessionId'; got: ${ex.message}",
        )
        assertTrue(
            "session_query(select=sessions)" in (ex.message ?: ""),
            "expected discoverability hint; got: ${ex.message}",
        )
    }

    @Test fun missingParentSessionThrows() = runTest {
        // Pin: parent not in store → error("not found").
        // Drift would let queries on ghost ids return
        // empty rows.
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runForksQuery(store, input("ghost-parent"), limit = 100, offset = 0)
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "expected not-found phrase; got: ${ex.message}",
        )
    }

    // ── Empty children → "no forks" body ─────────────────────

    @Test fun parentWithNoForksReturnsEmptyResultWithCitation() = runTest {
        // Pin: empty children → "Session X 'title' has no
        // forks." Drift to "missing parent's title" would
        // confuse the agent.
        val store = newStore()
        seedSession(store, sid = "parent", title = "Parent Title")

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        val output = result.data
        assertEquals(0, output.total, "total = 0 when no children")
        assertEquals(0, output.returned, "returned = 0 when no rows")
        assertEquals(0, output.rows.size, "rows JSON array empty")
        assertTrue(
            "no forks" in result.outputForLlm,
            "outputForLlm cites no-forks; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Parent Title" in result.outputForLlm,
            "outputForLlm cites parent's title; got: ${result.outputForLlm}",
        )
    }

    // ── Single-hop: grandchildren NOT included ───────────────

    @Test fun grandchildrenAreNotIncludedSingleHopOnly() = runTest {
        // Marquee single-hop pin: per kdoc "One hop only".
        // A grandchild bound to a child (not the
        // grandparent) must NOT surface in the
        // grandparent's forks. Drift to recursive walk
        // would silently fold the entire descendant tree.
        val store = newStore()
        seedSession(store, sid = "grandparent")
        seedSession(store, sid = "child", parentId = SessionId("grandparent"))
        seedSession(store, sid = "grandchild", parentId = SessionId("child"))

        val result = runForksQuery(
            store,
            input("grandparent"),
            limit = 100,
            offset = 0,
        )
        assertEquals(1, result.data.total, "only direct child counts")
        assertEquals(1, result.data.returned)
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationWithLimitTakesFirstNRows() = runTest {
        // Pin: limit caps the returned rows but the `total`
        // reports unfiltered count. Drift to "limit
        // applied to total" would silently truncate the
        // signal "more pages available."
        val store = newStore()
        seedSession(store, sid = "parent")
        for (i in 1..5) {
            seedSession(
                store,
                sid = "child-$i",
                parentId = SessionId("parent"),
                createdAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runForksQuery(store, input("parent"), limit = 2, offset = 0)
        assertEquals(5, result.data.total, "total reports unfiltered count")
        assertEquals(2, result.data.returned, "returned = limit")
        assertEquals(2, result.data.rows.size)
    }

    @Test fun paginationWithOffsetSkipsFirstNRows() = runTest {
        // Pin: drop(offset) skips the first N. Total stays
        // at unfiltered count.
        val store = newStore()
        seedSession(store, sid = "parent")
        for (i in 1..5) {
            seedSession(
                store,
                sid = "child-$i",
                parentId = SessionId("parent"),
                createdAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 3)
        assertEquals(5, result.data.total, "total still 5")
        assertEquals(2, result.data.returned, "5 - 3 offset = 2 returned")
    }

    @Test fun offsetBeyondTotalReturnsEmptyPage() = runTest {
        // Pin: when offset ≥ children.size, drop(offset)
        // produces an empty list — page is empty but
        // total still reports the original count.
        val store = newStore()
        seedSession(store, sid = "parent")
        seedSession(store, sid = "child-1", parentId = SessionId("parent"))

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 100)
        assertEquals(1, result.data.total, "total preserved")
        assertEquals(0, result.data.returned, "page empty")
    }

    // ── ForkRow shape ─────────────────────────────────────────

    @Test fun forkRowExposesAllSessionFields() = runTest {
        // Pin: each ForkRow carries id, projectId, title,
        // createdAt + updatedAt as epoch-ms, archived.
        // Drift in any field would break consumer-side
        // decode.
        val store = newStore()
        seedSession(store, sid = "parent")
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val updatedAt = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        seedSession(
            store,
            sid = "child-1",
            title = "My Child",
            parentId = SessionId("parent"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            archived = true,
        )

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"id\":\"child-1\"" in rowJson, "id field; got: $rowJson")
        assertTrue("\"projectId\":\"p\"" in rowJson, "projectId; got: $rowJson")
        assertTrue("\"title\":\"My Child\"" in rowJson, "title; got: $rowJson")
        assertTrue(
            "\"createdAtEpochMs\":${createdAt.toEpochMilliseconds()}" in rowJson,
            "createdAtEpochMs; got: $rowJson",
        )
        assertTrue(
            "\"updatedAtEpochMs\":${updatedAt.toEpochMilliseconds()}" in rowJson,
            "updatedAtEpochMs; got: $rowJson",
        )
        assertTrue("\"archived\":true" in rowJson, "archived; got: $rowJson")
    }

    // ── outputForLlm format ──────────────────────────────────

    @Test fun outputForLlmCitesCountAndParentForNonEmptyResult() = runTest {
        // Pin: format is "{N} of {total} fork(s) of
        // {parentId} '{title}', oldest first: …"
        val store = newStore()
        seedSession(store, sid = "parent", title = "Parent Title")
        seedSession(store, sid = "c1", title = "Child 1", parentId = SessionId("parent"))
        seedSession(store, sid = "c2", title = "Child 2", parentId = SessionId("parent"))

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        assertTrue("2 of 2" in result.outputForLlm, "count format; got: ${result.outputForLlm}")
        assertTrue("fork(s)" in result.outputForLlm, "fork(s) literal")
        assertTrue("Parent Title" in result.outputForLlm, "parent title cited")
        assertTrue("oldest first" in result.outputForLlm, "ordering cited")
        // First 5 rows enumerated as "id 'title'; ...".
        assertTrue("c1 'Child 1'" in result.outputForLlm)
        assertTrue("c2 'Child 2'" in result.outputForLlm)
    }

    @Test fun outputForLlmTruncatesAfterFiveRowsWithEllipsis() = runTest {
        // Pin: rows.take(5) + truncation suffix when
        // rows.size > 5. Drift would crowd the LLM's
        // context window with all rows.
        val store = newStore()
        seedSession(store, sid = "parent")
        for (i in 1..7) {
            seedSession(
                store,
                sid = "child-$i",
                parentId = SessionId("parent"),
                createdAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        assertTrue(result.outputForLlm.endsWith("; …"), "truncation suffix; got: ${result.outputForLlm}")
        // First 5 ids appear; later ones do not.
        assertTrue("child-1 " in result.outputForLlm)
        assertTrue("child-5 " in result.outputForLlm)
        assertTrue("child-6 'session-child-6'" !in result.outputForLlm, "6th row excluded from preview")
    }

    @Test fun outputForLlmNoEllipsisWhenFiveOrFewerRows() = runTest {
        // Pin: exactly 5 rows → no ellipsis.
        val store = newStore()
        seedSession(store, sid = "parent")
        for (i in 1..5) {
            seedSession(
                store,
                sid = "child-$i",
                parentId = SessionId("parent"),
                createdAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        assertTrue(
            !result.outputForLlm.endsWith("; …"),
            "no ellipsis at exactly 5; got: ${result.outputForLlm}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsForks() = runTest {
        val store = newStore()
        seedSession(store, sid = "parent")
        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_FORKS, result.data.select)
    }

    // ── Title format ─────────────────────────────────────────

    @Test fun toolResultTitleCitesParentIdAndCounts() = runTest {
        val store = newStore()
        seedSession(store, sid = "parent")
        seedSession(store, sid = "c1", parentId = SessionId("parent"))
        seedSession(store, sid = "c2", parentId = SessionId("parent"))

        val result = runForksQuery(store, input("parent"), limit = 1, offset = 0)
        assertTrue(
            "parent" in result.title!!,
            "title cites parentId; got: ${result.title}",
        )
        assertTrue(
            "(1/2)" in result.title!!,
            "title cites returned/total; got: ${result.title}",
        )
    }

    // ── Result row order matches store's listChildSessions ──

    @Test fun rowsReportInStoreReturnedOrder() = runTest {
        // Pin: handler does NOT re-sort children — it
        // takes whatever order listChildSessions returns
        // (kdoc says "oldest first" reflecting the store's
        // contract, not handler-side sort). Drift to
        // "handler sorts" would couple this query to the
        // store's invariant change.
        val store = newStore()
        seedSession(store, sid = "parent")
        for (i in 1..3) {
            seedSession(
                store,
                sid = "c-$i",
                parentId = SessionId("parent"),
                createdAt = Instant.fromEpochMilliseconds(
                    1_700_000_000_000L + i * 1_000L,
                ),
            )
        }

        val ordered = store.listChildSessions(SessionId("parent")).map { it.id.value }
        val result = runForksQuery(store, input("parent"), limit = 100, offset = 0)
        val resultIds = result.data.rows.map { row ->
            row.toString().substringAfter("\"id\":\"").substringBefore("\"")
        }
        assertContentEquals(
            ordered,
            resultIds,
            "handler returns rows in store-order, not re-sorted",
        )
    }
}
