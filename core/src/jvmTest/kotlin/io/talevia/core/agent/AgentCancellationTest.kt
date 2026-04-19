package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCancellationTest {

    @Test
    fun cancelFinalisesAssistantWithCancelledReason() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val sid = SessionId("cancel-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )

        val started = CompletableDeferred<Unit>()
        val provider = HangingProvider(started)

        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val runJob = async { agent.run(RunInput(sid, "hi", ModelRef("fake", "x"))) }

        // Wait until the provider has started streaming — i.e. we're suspended inside the turn.
        started.await()
        assertTrue(agent.isRunning(sid), "agent should report running after stream starts")

        assertTrue(agent.cancel(sid))
        assertFailsWith<CancellationException> { runJob.await() }

        assertFalse(agent.isRunning(sid), "agent should no longer be running after cancel resolves")
        val messages = store.listMessages(sid).filterIsInstance<Message.Assistant>()
        assertEquals(1, messages.size)
        assertEquals(FinishReason.CANCELLED, messages.single().finish)

        driver.close()
    }

    @Test
    fun cancelOnIdleSessionReturnsFalse() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val agent = Agent(
            provider = FakeProvider(emptyList()),
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
        assertFalse(agent.cancel(SessionId("no-such-session")))

        driver.close()
    }

    /** Provider whose stream signals when it starts and then suspends forever. */
    private class HangingProvider(private val started: CompletableDeferred<Unit>) : LlmProvider {
        override val id = "hanging"
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            started.complete(Unit)
            // Yield once so the caller observes `isRunning = true` before we suspend forever.
            yield()
            awaitCancellation()
        }
    }
}
