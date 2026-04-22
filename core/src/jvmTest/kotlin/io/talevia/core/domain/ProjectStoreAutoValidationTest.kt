package io.talevia.core.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SqlDelightProjectStore.get` runs a lightweight source-DAG validation on
 * every successful load and publishes `BusEvent.ProjectValidationWarning`
 * if issues surface. The load itself **does not throw** — the project is
 * returned verbatim, so pre-existing bad blobs stay readable and the user
 * can fix incrementally via `validate_project` + source-mutation tools.
 */
class ProjectStoreAutoValidationTest {

    private fun node(id: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = "test.kind",
            body = JsonObject(emptyMap()),
            parents = parents.map { SourceRef(SourceNodeId(it)) },
        )

    @Test fun cleanProjectDoesNotPublish() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val warnings = mutableListOf<BusEvent.ProjectValidationWarning>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            bus.events.collect { e ->
                if (e is BusEvent.ProjectValidationWarning) warnings.add(e)
            }
        }
        val store = SqlDelightProjectStore(db, bus = bus)

        var source: Source = Source.EMPTY
        source = source.addNode(node("a"))
        source = source.addNode(node("b", parents = listOf("a")))
        store.upsert("clean", Project(id = ProjectId("p-clean"), timeline = Timeline(), source = source))

        val reloaded = store.get(ProjectId("p-clean"))
        yield()
        assertEquals(ProjectId("p-clean"), reloaded?.id)
        assertTrue(warnings.isEmpty(), "clean project must not emit a validation warning, got: $warnings")
        job.cancel()
    }

    @Test fun projectWithDanglingParentPublishesWarningButStillReturns() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val warnings = mutableListOf<BusEvent.ProjectValidationWarning>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            bus.events.collect { e ->
                if (e is BusEvent.ProjectValidationWarning) warnings.add(e)
            }
        }
        val store = SqlDelightProjectStore(db, bus = bus)

        // Project with a dangling parent (child references ghost id).
        var source: Source = Source.EMPTY
        source = source.addNode(node("child", parents = listOf("ghost")))
        store.upsert("bad", Project(id = ProjectId("p-bad"), timeline = Timeline(), source = source))

        val reloaded = store.get(ProjectId("p-bad"))
        yield()

        // Still returns the project — auto-validation is non-throwing.
        assertEquals(ProjectId("p-bad"), reloaded?.id)
        assertTrue(reloaded?.source?.nodes?.isNotEmpty() == true)

        // And emits one warning naming the project + the issue.
        assertEquals(1, warnings.size, "expected 1 warning, got: $warnings")
        assertEquals(ProjectId("p-bad"), warnings.first().projectId)
        assertTrue(warnings.first().issues.any { "ghost" in it }, warnings.first().issues.toString())
        job.cancel()
    }

    @Test fun nullBusRigStillReadsCleanly() = runTest {
        // Without a bus, no warning is published but get() must still work.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db) // bus = null by default

        var source: Source = Source.EMPTY
        source = source.addNode(node("child", parents = listOf("ghost")))
        store.upsert("bad2", Project(id = ProjectId("p-bad2"), timeline = Timeline(), source = source))

        val reloaded = store.get(ProjectId("p-bad2"))
        assertEquals(ProjectId("p-bad2"), reloaded?.id)
    }

    @Test fun getOfMissingProjectReturnsNullWithNoWarning() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val warnings = mutableListOf<BusEvent.ProjectValidationWarning>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            bus.events.collect { e ->
                if (e is BusEvent.ProjectValidationWarning) warnings.add(e)
            }
        }
        val store = SqlDelightProjectStore(db, bus = bus)

        val reloaded = store.get(ProjectId("does-not-exist"))
        yield()
        assertEquals(null, reloaded)
        assertTrue(warnings.isEmpty())
        job.cancel()
    }
}
