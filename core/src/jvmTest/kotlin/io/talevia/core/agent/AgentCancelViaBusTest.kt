package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * End-to-end: publishing [BusEvent.SessionCancelRequested] on the shared bus
 * cancels an in-flight `Agent.run` through the same path as a direct
 * [Agent.cancel] call — without the publisher needing to hold an Agent
 * reference. Also pins the latency budget so a slow subscriber doesn't
 * regress user-facing Ctrl+C responsiveness unnoticed.
 */
class AgentCancelViaBusTest {

    @Test
    fun cancelRequestedEventCancelsInFlightRunViaBus(): TestResult = runTest {
        // Real dispatchers so the subscription + job cancel propagate through
        // actual thread scheduling rather than deterministic virtual time.
        // runTest's virtual clock doesn't observe a real-world latency budget;
        // the test's point is precisely that wallclock latency stays small.
        withContext(Dispatchers.Default) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                TaleviaDb.Schema.create(it)
            }
            val db = TaleviaDb(driver)
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)

            val sid = SessionId("cancel-via-bus-session")
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

            val streamStarted = CompletableDeferred<Unit>()
            val provider = HangingProvider(streamStarted)
            val agent = Agent(
                provider = provider,
                registry = ToolRegistry(),
                store = store,
                permissions = AllowAllPermissionService(),
                bus = bus,
            )

            // Race guard: without awaiting the subscription, a fast publish
            // can land before the Agent's SharedFlow subscriber is registered
            // and the event is silently dropped (MutableSharedFlow has no
            // replay). Matches production behaviour too — the Agent is
            // constructed once at app startup and the first cancel only
            // happens much later, so this `await` is effectively a no-op
            // on hot paths.
            agent.awaitCancelSubscriptionReady()

            val runJob = async {
                agent.run(RunInput(sid, "hi", ModelRef("hanging", "x")))
            }
            streamStarted.await()
            assertTrue(agent.isRunning(sid))

            // Publish and measure how long until the Agent's run observes
            // cancellation. Budget is 500 ms — tight enough to catch a
            // regression where the subscription accidentally runs on a
            // stalled dispatcher or adds an artificial delay, and loose
            // enough to survive a slow CI worker (VISION §5.4 rubric).
            val started = TimeSource.Monotonic.markNow()
            bus.publish(BusEvent.SessionCancelRequested(sid))
            withTimeout(5.seconds) {
                assertFailsWith<CancellationException> { runJob.await() }
            }
            val elapsedMs = started.elapsedNow().inWholeMilliseconds
            assertTrue(
                elapsedMs < 500,
                "cancel-via-bus latency was ${elapsedMs}ms, expected < 500ms",
            )

            assertFalse(agent.isRunning(sid))
            val messages = store.listMessages(sid).filterIsInstance<Message.Assistant>()
            assertEquals(1, messages.size)
            assertEquals(FinishReason.CANCELLED, messages.single().finish)

            driver.close()
        }
    }

    @Test
    fun cancelRequestedForIdleSessionIsSilentNoOp(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                TaleviaDb.Schema.create(it)
            }
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
            agent.awaitCancelSubscriptionReady()

            // No in-flight run → publishing should not throw and should
            // not wake anyone up. Pins the "idle cancel is a no-op"
            // contract so it stays aligned with `Agent.cancel(sid)`
            // returning false on idle.
            bus.publish(BusEvent.SessionCancelRequested(SessionId("ghost-session")))
            yield()

            driver.close()
        }
    }

    /** Stream-only provider that signals "we're streaming" and then hangs. */
    private class HangingProvider(
        private val started: CompletableDeferred<Unit>,
    ) : LlmProvider {
        override val id = "hanging"
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            started.complete(Unit)
            yield()
            awaitCancellation()
        }
    }
}
