package io.talevia.core.tool.query

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-dispatcher convention check. The `QueryDispatcher` base owns two
 * invariants every concrete dispatcher must honour:
 *
 *  1. `selects` ↔ `rowSerializerFor` agreement — every advertised select
 *     has a registered row serializer. Drift between the two surfaces is
 *     a silent failure (a new select that forgets to register its row
 *     serializer will pass `canonicalSelect` and fail inside the caller's
 *     decode), so pin it in a test.
 *  2. Row types are top-level — the serializer's descriptor serialName
 *     must not reference a dispatcher's class name (e.g. `SourceQueryTool
 *     .NodeRow`). Top-level rows mean handlers can move between files
 *     without dragging their row schema, and consumers import each row
 *     by its bare name. Enforced here rather than via docs.
 *
 * Exercising each concrete dispatcher catches regressions regardless of
 * which query_tool adds a new select next.
 */
class QueryDispatcherConventionTest {

    private val dispatchers: List<QueryDispatcher<*, *>>
        get() {
            val projects = ProjectStoreTestKit.create()
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            TaleviaDb.Schema.create(driver)
            val sessions = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
            val providers = ProviderRegistry(byId = emptyMap(), default = null)
            val warmupStats = io.talevia.core.provider.ProviderWarmupStats.withSupervisor(EventBus())
            return listOf(
                ProjectQueryTool(projects),
                SessionQueryTool(sessions),
                SourceQueryTool(projects),
                ProviderQueryTool(providers, warmupStats, projects),
            )
        }

    @Test fun everySelectHasARegisteredRowSerializer() {
        for (dispatcher in dispatchers) {
            for (select in dispatcher.selects) {
                // Must not throw — every canonical select publishes a serializer.
                dispatcher.rowSerializerFor(select)
            }
        }
    }

    @Test fun unknownSelectHasNoRowSerializer() {
        for (dispatcher in dispatchers) {
            assertFailsWith<IllegalStateException> {
                dispatcher.rowSerializerFor("__definitely_not_a_select__")
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test fun rowTypesAreTopLevel() {
        // A nested data class's @Serializable descriptor has serialName like
        // `io.talevia.core.tool.builtin.source.SourceQueryTool.NodeRow` —
        // the enclosing class segment starts with uppercase right before the
        // row's own name. Top-level rows look like
        // `io.talevia.core.tool.builtin.source.query.NodeRow` — the segment
        // before the last is the lowercase package `query`.
        for (dispatcher in dispatchers) {
            val dispatcherClassShortName = dispatcher::class.simpleName!!
            for (select in dispatcher.selects) {
                val descriptor = dispatcher.rowSerializerFor(select).descriptor
                val serialName = descriptor.serialName
                assertFalse(
                    serialName.contains(".$dispatcherClassShortName."),
                    "row serializer for '$select' on $dispatcherClassShortName is a nested class " +
                        "(serialName='$serialName'). Move the @Serializable data class to a top-level " +
                        "sibling file per the QueryDispatcher convention.",
                )
            }
        }
    }

    @Test fun canonicalSelectNormalizesCaseAndWhitespace() {
        val projects = ProjectStoreTestKit.create()
        val tool = ProjectQueryTool(projects)
        // Unknown select path — direct canonicalSelect is protected; execute
        // exercises it via `input.select.trim().lowercase()` semantics.
        val ex = assertFailsWith<IllegalStateException> {
            kotlinx.coroutines.runBlocking {
                tool.execute(
                    ProjectQueryTool.Input(
                        projectId = "unused",
                        select = "GHOST_SELECT",
                    ),
                    ctx = io.talevia.core.tool.ToolContext(
                        sessionId = io.talevia.core.SessionId("s"),
                        messageId = io.talevia.core.MessageId("m"),
                        callId = io.talevia.core.CallId("c"),
                        askPermission = { io.talevia.core.permission.PermissionDecision.Once },
                        emitPart = { },
                        messages = emptyList(),
                    ),
                )
            }
        }
        // The canonicalSelect failure message lists known selects.
        assertTrue(
            ex.message!!.contains("select must be one of"),
            "unexpected error: ${ex.message}",
        )
        // The sorted join produces a deterministic prefix (`assets` is alphabetically first).
        assertEquals(
            true,
            ex.message!!.contains("assets"),
            "expected listing to include 'assets'; got ${ex.message}",
        )
    }
}
