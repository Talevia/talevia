package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListProjectsToolTest {

    /**
     * Mutable clock so we can stamp staggered `time_updated` / `time_created`
     * values on seeded projects — `SqlDelightProjectStore` reads the current
     * time from its injected [Clock] during [SqlDelightProjectStore.upsert].
     */
    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private data class Rig(
        val store: SqlDelightProjectStore,
        val clock: MutableClock,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = SqlDelightProjectStore(TaleviaDb(driver), clock = clock)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, clock, ctx)
    }

    private suspend fun seed(
        rig: Rig,
        id: String,
        title: String,
        at: Instant,
    ) {
        rig.clock.instant = at
        rig.store.upsert(title, Project(id = ProjectId(id), timeline = Timeline()))
    }

    @Test fun emptyStoreReturnsEmpty() = runTest {
        val rig = rig()
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(),
            rig.ctx,
        ).data
        assertEquals(0, out.totalCount)
        assertEquals(0, out.returnedCount)
        assertTrue(out.projects.isEmpty())
    }

    @Test fun defaultSortIsUpdatedDescending() = runTest {
        val rig = rig()
        seed(rig, "p-a", "Apple", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-b", "Banana", Instant.parse("2026-01-02T00:00:00Z"))
        seed(rig, "p-c", "Cherry", Instant.parse("2026-01-03T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(),
            rig.ctx,
        ).data
        assertEquals(3, out.totalCount)
        assertEquals(3, out.returnedCount)
        assertEquals(listOf("p-c", "p-b", "p-a"), out.projects.map { it.id })
    }

    @Test fun sortByCreatedDescOrdersNewestFirst() = runTest {
        val rig = rig()
        seed(rig, "p-a", "Apple", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-b", "Banana", Instant.parse("2026-01-02T00:00:00Z"))
        seed(rig, "p-c", "Cherry", Instant.parse("2026-01-03T00:00:00Z"))
        // Touch p-a after the others — updatedAt moves, but createdAt is pinned on insert.
        seed(rig, "p-a", "Apple", Instant.parse("2026-01-04T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(sortBy = "created-desc"),
            rig.ctx,
        ).data
        assertEquals(listOf("p-c", "p-b", "p-a"), out.projects.map { it.id })
    }

    @Test fun sortByTitleIsCaseInsensitive() = runTest {
        val rig = rig()
        seed(rig, "p-1", "banana", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-2", "Apple", Instant.parse("2026-01-02T00:00:00Z"))
        seed(rig, "p-3", "cherry", Instant.parse("2026-01-03T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(sortBy = "title"),
            rig.ctx,
        ).data
        assertEquals(listOf("Apple", "banana", "cherry"), out.projects.map { it.title })
    }

    @Test fun sortByIdIsAlphabeticAscending() = runTest {
        val rig = rig()
        seed(rig, "p-c", "gamma", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-a", "alpha", Instant.parse("2026-01-02T00:00:00Z"))
        seed(rig, "p-b", "beta", Instant.parse("2026-01-03T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(sortBy = "id"),
            rig.ctx,
        ).data
        assertEquals(listOf("p-a", "p-b", "p-c"), out.projects.map { it.id })
    }

    @Test fun limitCapsResponse() = runTest {
        val rig = rig()
        for (i in 1..5) {
            seed(rig, "p-$i", "Project $i", Instant.parse("2026-01-0${i}T00:00:00Z"))
        }
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(limit = 2),
            rig.ctx,
        ).data
        assertEquals(5, out.totalCount)
        assertEquals(2, out.returnedCount)
        assertEquals(2, out.projects.size)
    }

    @Test fun limitClampedToMax() = runTest {
        val rig = rig()
        for (i in 1..3) {
            seed(rig, "p-$i", "Project $i", Instant.parse("2026-01-0${i}T00:00:00Z"))
        }
        // No exception — clamped silently down to MAX=500, which still returns all 3 seeded.
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(limit = 999_999),
            rig.ctx,
        ).data
        assertEquals(3, out.totalCount)
        assertEquals(3, out.returnedCount)
    }

    @Test fun limitClampedToMinimum() = runTest {
        val rig = rig()
        for (i in 1..3) {
            seed(rig, "p-$i", "Project $i", Instant.parse("2026-01-0${i}T00:00:00Z"))
        }
        // limit=0 silently clamps to 1 rather than raising — same pattern as list_lockfile_entries.
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(limit = 0),
            rig.ctx,
        ).data
        assertEquals(3, out.totalCount)
        assertEquals(1, out.returnedCount)
    }

    @Test fun sortByInvalidFailsLoudly() = runTest {
        val rig = rig()
        seed(rig, "p-a", "Apple", Instant.parse("2026-01-01T00:00:00Z"))
        val ex = assertFailsWith<IllegalArgumentException> {
            ListProjectsTool(rig.store).execute(
                ListProjectsTool.Input(sortBy = "ghost"),
                rig.ctx,
            )
        }
        // Error message lists every accepted value so the agent can self-correct.
        assertTrue(ex.message!!.contains("sortBy"), ex.message)
        assertTrue(ex.message!!.contains("updated-desc"), ex.message)
        assertTrue(ex.message!!.contains("created-desc"), ex.message)
        assertTrue(ex.message!!.contains("title"), ex.message)
        assertTrue(ex.message!!.contains("id"), ex.message)
    }

    @Test fun sortByComposesWithLimit() = runTest {
        val rig = rig()
        seed(rig, "p-1", "echo", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-2", "alpha", Instant.parse("2026-01-02T00:00:00Z"))
        seed(rig, "p-3", "charlie", Instant.parse("2026-01-03T00:00:00Z"))
        seed(rig, "p-4", "bravo", Instant.parse("2026-01-04T00:00:00Z"))
        seed(rig, "p-5", "delta", Instant.parse("2026-01-05T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(sortBy = "title", limit = 3),
            rig.ctx,
        ).data
        assertEquals(5, out.totalCount)
        assertEquals(3, out.returnedCount)
        assertEquals(listOf("alpha", "bravo", "charlie"), out.projects.map { it.title })
    }

    @Test fun sortByIsCaseInsensitiveAndTrimmed() = runTest {
        val rig = rig()
        seed(rig, "p-a", "Apple", Instant.parse("2026-01-01T00:00:00Z"))
        seed(rig, "p-b", "Banana", Instant.parse("2026-01-02T00:00:00Z"))
        val out = ListProjectsTool(rig.store).execute(
            ListProjectsTool.Input(sortBy = "  TITLE  "),
            rig.ctx,
        ).data
        assertEquals(listOf("Apple", "Banana"), out.projects.map { it.title })
    }
}
