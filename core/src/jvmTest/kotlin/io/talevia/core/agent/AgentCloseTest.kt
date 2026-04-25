package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [Agent.close] — the AutoCloseable contract that cancels the
 * agent's background scope (cancel-watcher subscription, in-flight titler
 * launches, future fire-and-forget work).
 *
 * Strategy: pass an injected [CoroutineScope] as the `backgroundScope`
 * constructor argument so the test can directly observe its `isActive`
 * state before and after `close()`. The default-supplied scope would be
 * private and unreachable; the constructor exposes the seam for exactly
 * this kind of observation.
 */
class AgentCloseTest {

    private class EmptyProvider : LlmProvider {
        override val id: String = "empty"
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    private fun newAgent(backgroundScope: CoroutineScope): Agent {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return Agent(
            provider = EmptyProvider(),
            registry = ToolRegistry(),
            store = SqlDelightSessionStore(TaleviaDb(driver), EventBus()),
            permissions = AllowAllPermissionService(),
            bus = EventBus(),
            backgroundScope = backgroundScope,
        )
    }

    @Test
    fun closeCancelsTheInjectedBackgroundScope(): TestResult = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val agent = newAgent(scope)
        assertTrue(scope.isActive, "background scope must be live before close()")
        agent.close()
        assertFalse(scope.isActive, "close() must cancel the injected background scope")
    }

    @Test
    fun closeIsIdempotent(): TestResult = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val agent = newAgent(scope)
        agent.close()
        agent.close() // must not throw on a second close
        assertFalse(scope.isActive)
    }
}
