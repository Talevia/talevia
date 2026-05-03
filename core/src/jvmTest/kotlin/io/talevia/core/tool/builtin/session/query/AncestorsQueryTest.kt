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
 * Direct tests for [runAncestorsQuery] —
 * `core/tool/builtin/session/query/AncestorsQuery.kt`. The
 * `select=ancestors` handler walking the parent chain
 * from a session up to the root, child-first. Sister to
 * cycle 180's ForksQuery (which walks downward). Cycle 181
 * audit: 71 LOC, 0 direct test refs (the source-DAG
 * AncestorsQuery in a different package is tested but the
 * session-graph variant was never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Walk order is child-first → root.** Per kdoc:
 *    "first row is the immediate parent, last is the
 *    root." Drift to "root-first" (reverse) would shuffle
 *    every ancestor list visible to the agent.
 *
 * 2. **Root session (parentId=null) → empty chain, "is a
 *    root" body.** A session with no parent has zero
 *    ancestors. Drift to "throw" would crash on root
 *    sessions; drift to "include self" would mislead.
 *
 * 3. **Cycle-safe via visited set + dangling-parent
 *    early-break.** Per kdoc: "Cycle-safe via visited
 *    set." A pathological parentId chain forming a cycle
 *    must not loop forever (the visited set's
 *    `if (!visited.add(cursor)) break` short-circuits).
 *    Plus: a parent reference that doesn't resolve in
 *    the store (`getSession(cursor) ?: break`) breaks
 *    cleanly without throwing.
 */
class AncestorsQueryTest {

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
        archived: Boolean = false,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                parentId = parentId,
                createdAt = now,
                updatedAt = now,
                archived = archived,
            ),
        )
        return sessionId
    }

    private fun input(sessionId: String?): SessionQueryTool.Input =
        SessionQueryTool.Input(
            select = SessionQueryTool.SELECT_ANCESTORS,
            sessionId = sessionId,
        )

    private fun rowIds(rows: kotlinx.serialization.json.JsonArray): List<String> =
        rows.map { row ->
            row.toString().substringAfter("\"id\":\"").substringBefore("\"")
        }

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runAncestorsQuery(store, input(null), limit = 100, offset = 0)
        }
        assertTrue(
            "requires sessionId" in (ex.message ?: ""),
        )
    }

    @Test fun missingStartSessionThrows() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            runAncestorsQuery(store, input("ghost"), limit = 100, offset = 0)
        }
        assertTrue("not found" in (ex.message ?: ""))
    }

    // ── Root session has no ancestors ────────────────────────

    @Test fun rootSessionReturnsEmptyChainWithRootBodyMessage() = runTest {
        // Marquee root pin: a session with parentId=null
        // returns an empty chain, body says "is a root."
        // Drift to "include self" would mislead consumers
        // about chain identity.
        val store = newStore()
        seedSession(store, sid = "root", title = "Root Title", parentId = null)

        val result = runAncestorsQuery(store, input("root"), limit = 100, offset = 0)
        assertEquals(0, result.data.total, "total = 0 for root")
        assertEquals(0, result.data.returned)
        assertTrue(
            "is a root" in result.outputForLlm,
            "outputForLlm cites root; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Root Title" in result.outputForLlm,
            "outputForLlm cites session title; got: ${result.outputForLlm}",
        )
    }

    // ── Walk order: child-first → root ───────────────────────

    @Test fun chainOrderIsChildFirstThenRoot() = runTest {
        // Marquee child-first pin: kdoc says first row is
        // immediate parent, last is root. Drift to root-
        // first (reverse) would surface ancestors in the
        // wrong order on every list rendering.
        //
        // grandparent ← parent ← child (start)
        val store = newStore()
        seedSession(store, sid = "grandparent")
        seedSession(store, sid = "parent", parentId = SessionId("grandparent"))
        seedSession(store, sid = "child", parentId = SessionId("parent"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        assertEquals(2, result.data.total, "two ancestors: parent + grandparent")
        assertContentEquals(
            listOf("parent", "grandparent"),
            rowIds(result.data.rows),
            "child-first → root order",
        )
    }

    // ── Long chain ───────────────────────────────────────────

    @Test fun longChainProducesAllAncestorsInOrder() = runTest {
        // Pin: a 5-deep chain produces 4 ancestor rows in
        // child-first → root order.
        // root ← g3 ← g2 ← g1 ← child (start)
        val store = newStore()
        seedSession(store, sid = "root")
        seedSession(store, sid = "g3", parentId = SessionId("root"))
        seedSession(store, sid = "g2", parentId = SessionId("g3"))
        seedSession(store, sid = "g1", parentId = SessionId("g2"))
        seedSession(store, sid = "child", parentId = SessionId("g1"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        assertEquals(4, result.data.total)
        assertContentEquals(
            listOf("g1", "g2", "g3", "root"),
            rowIds(result.data.rows),
            "4 ancestors in child-first order",
        )
    }

    // ── Cycle safety ─────────────────────────────────────────

    @Test fun parentIdCycleDoesNotInfiniteLoop() = runTest {
        // Marquee cycle-safety pin: kdoc says "Cycle-safe
        // via visited set." A pathological cycle a → b → a
        // (legacy data, hand-edited) must not loop forever.
        //
        // Build a cycle by creating parent first then
        // updating child to reference parent.
        val store = newStore()
        seedSession(store, sid = "a")
        seedSession(store, sid = "b", parentId = SessionId("a"))
        // Now make a's parent be b — completing the cycle.
        store.updateSession(
            store.getSession(SessionId("a"))!!.copy(parentId = SessionId("b")),
        )

        // Walking from a: a → (parent=b) → b's row added,
        // (b's parent=a) → a is in visited set → break.
        val result = runAncestorsQuery(store, input("a"), limit = 100, offset = 0)
        // The chain has just b (the visited-set break
        // happens after b is added, before re-adding a).
        assertEquals(1, result.data.total, "cycle detection breaks after adding b once")
        assertEquals(
            listOf("b"),
            rowIds(result.data.rows),
        )
    }

    @Test fun selfLoopReturnsEmptyChain() = runTest {
        // Pin: self-loop (a's parent is a) — `visited`
        // already contains `start.id`, so the first
        // `visited.add(cursor)` returns false and breaks
        // immediately. Empty chain, "root" body.
        val store = newStore()
        seedSession(store, sid = "a")
        // Update to make a its own parent.
        store.updateSession(
            store.getSession(SessionId("a"))!!.copy(parentId = SessionId("a")),
        )

        val result = runAncestorsQuery(store, input("a"), limit = 100, offset = 0)
        // visited starts with start.id ("a"); cursor =
        // a's parentId = "a"; visited.add("a") returns
        // false → break with empty chain. The body falls
        // into the "is a root" branch because chain is
        // empty.
        assertEquals(0, result.data.total)
        assertTrue("is a root" in result.outputForLlm)
    }

    // ── Dangling parent reference ────────────────────────────

    @Test fun danglingParentRefBreaksWithoutThrow() = runTest {
        // Pin: parent ref that doesn't resolve in store
        // (`getSession(cursor) ?: break`) breaks cleanly,
        // returning whatever ancestors were already added.
        // Drift to "throw on missing" would crash queries
        // on legacy data with deleted parents.
        //
        // We can't directly seed an orphaned reference
        // through the store API (createSession requires
        // valid parent), so test this via an updateSession
        // that drops the chain mid-walk.
        val store = newStore()
        seedSession(store, sid = "root")
        seedSession(store, sid = "child", parentId = SessionId("root"))
        // After child created, delete root — now child
        // has a dangling parentId.
        store.deleteSession(SessionId("root"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        // Walk: cursor=root, visited.add("root") = true,
        // getSession(root) = null → break. Chain is empty.
        assertEquals(0, result.data.total, "dangling parent → empty chain (graceful)")
        assertTrue("is a root" in result.outputForLlm)
    }

    // ── Pagination ───────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsTotalReportsFullChain() = runTest {
        // Pin: limit caps the page; total reports
        // unfiltered chain length. Drift to "limit applied
        // to total" would silently truncate the
        // ancestor-count signal.
        val store = newStore()
        seedSession(store, sid = "g3")
        seedSession(store, sid = "g2", parentId = SessionId("g3"))
        seedSession(store, sid = "g1", parentId = SessionId("g2"))
        seedSession(store, sid = "child", parentId = SessionId("g1"))

        val result = runAncestorsQuery(store, input("child"), limit = 2, offset = 0)
        assertEquals(3, result.data.total, "total reports full chain")
        assertEquals(2, result.data.returned)
        assertContentEquals(listOf("g1", "g2"), rowIds(result.data.rows))
    }

    @Test fun paginationOffsetSkipsFirstNAncestors() = runTest {
        // Pin: drop(offset) skips first N child-first
        // ancestors. Useful for "show me only the deeper
        // ancestors" UI.
        val store = newStore()
        seedSession(store, sid = "g3")
        seedSession(store, sid = "g2", parentId = SessionId("g3"))
        seedSession(store, sid = "g1", parentId = SessionId("g2"))
        seedSession(store, sid = "child", parentId = SessionId("g1"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 2)
        assertEquals(3, result.data.total)
        assertEquals(1, result.data.returned, "drop(2) leaves 1")
        assertContentEquals(listOf("g3"), rowIds(result.data.rows))
    }

    // ── AncestorRow shape ────────────────────────────────────

    @Test fun ancestorRowExposesParentIdAsNullableString() = runTest {
        // Pin: row.parentId is null for the root, non-null
        // for intermediate. Drift to "always non-null" would
        // confuse decoders that branch on root-detection.
        val store = newStore()
        seedSession(store, sid = "root")
        seedSession(store, sid = "child", parentId = SessionId("root"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        // The single ancestor (root) has parentId = null.
        val rowJson = result.data.rows[0].toString()
        assertTrue(
            "\"parentId\":null" in rowJson || "\"parentId\":\"" !in rowJson,
            "root's parentId surfaces as null; got: $rowJson",
        )
    }

    @Test fun ancestorRowExposesArchivedFlag() = runTest {
        val store = newStore()
        seedSession(store, sid = "archived-parent", archived = true)
        seedSession(store, sid = "child", parentId = SessionId("archived-parent"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"archived\":true" in rowJson, "archived flag preserved; got: $rowJson")
    }

    // ── outputForLlm format conventions ──────────────────────

    @Test fun outputForLlmCitesParentFirstRootOrdering() = runTest {
        // Pin: format "{N} of {total} ancestor(s) of {id}
        // '{title}', parent-first → root: ..."
        val store = newStore()
        seedSession(store, sid = "root", title = "Root")
        seedSession(store, sid = "parent", title = "Parent", parentId = SessionId("root"))
        seedSession(store, sid = "child", title = "Child", parentId = SessionId("parent"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        assertTrue("2 of 2" in result.outputForLlm, "count format")
        assertTrue("ancestor(s)" in result.outputForLlm, "literal phrase")
        assertTrue("Child" in result.outputForLlm, "start session title cited")
        assertTrue("parent-first → root" in result.outputForLlm, "ordering cited")
        assertTrue("parent 'Parent'" in result.outputForLlm)
        assertTrue("root 'Root'" in result.outputForLlm)
    }

    @Test fun outputForLlmTruncatesAfterFiveAncestorsWithEllipsis() = runTest {
        // Pin: page.take(5) + "; …" suffix when page.size > 5.
        val store = newStore()
        seedSession(store, sid = "root")
        var prev = "root"
        for (i in 1..7) {
            val curr = "g-$i"
            seedSession(store, sid = curr, parentId = SessionId(prev))
            prev = curr
        }
        // Now `g-7` is the start; chain has 7 ancestors
        // (g-6, g-5, g-4, g-3, g-2, g-1, root).

        val result = runAncestorsQuery(store, input("g-7"), limit = 100, offset = 0)
        assertEquals(7, result.data.total)
        assertTrue(result.outputForLlm.endsWith("; …"), "truncation suffix appears; got: ${result.outputForLlm}")
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsAncestors() = runTest {
        val store = newStore()
        seedSession(store, sid = "root")
        val result = runAncestorsQuery(store, input("root"), limit = 100, offset = 0)
        assertEquals(SessionQueryTool.SELECT_ANCESTORS, result.data.select)
    }

    @Test fun toolResultTitleCitesStartIdAndCounts() = runTest {
        val store = newStore()
        seedSession(store, sid = "root")
        seedSession(store, sid = "child", parentId = SessionId("root"))

        val result = runAncestorsQuery(store, input("child"), limit = 100, offset = 0)
        assertTrue("child" in result.title!!)
        assertTrue("(1/1)" in result.title!!, "(returned/total) format; got: ${result.title}")
    }
}
