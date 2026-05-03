package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for the two top-level functions in
 * `core/agent/AgentRunFinalizer.kt`:
 * [finalizeCancelledRun] and [finalizeFailedRun]. Both are
 * side-effect bundles for `Agent.run`'s terminal catch
 * chain, with zero direct test references — the existing
 * `CancelFinalizerTest` exercises the inner
 * `finalizeCancelled` stamping helper but NOT these
 * orchestrating wrappers.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Cancel-path publishes BOTH `SessionCancelled` AND
 *    `AgentRunStateChanged(Cancelled)`.** Subscribers that
 *    only care about run-state changes need the
 *    `AgentRunStateChanged` event; subscribers tracking
 *    "is this session running" need the explicit
 *    `SessionCancelled`. Drift to publishing only one would
 *    silently strand half the subscriber graph.
 *
 * 2. **Error-path publishes a single
 *    `AgentRunStateChanged(Failed)` with the throwable's
 *    message OR class name fallback.** The fallback chain
 *    `cause.message ?: cause::class.simpleName ?? "unknown"`
 *    protects against `Throwable()` with neither.
 *
 * 3. **Both terminal events carry `handle.lastRetryAttempt`.**
 *    Subscribers downstream correlate the terminal state
 *    with the preceding retry — drift would erase that
 *    trail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunFinalizerTest {

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedAssistantMessage(
        store: SqlDelightSessionStore,
        sid: String = "s1",
        aid: String = "a1",
        finish: FinishReason? = null,
    ) {
        val sessionId = SessionId(sid)
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        store.appendMessage(
            Message.User(
                id = MessageId("u1"),
                sessionId = sessionId,
                createdAt = now,
                agent = "test",
                model = ModelRef("anthropic", "claude-3-5"),
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = MessageId(aid),
                sessionId = sessionId,
                createdAt = now,
                parentId = MessageId("u1"),
                model = ModelRef("anthropic", "claude-3-5"),
                finish = finish,
            ),
        )
    }

    // ── cancel path: dual emission + ordering ──────────────────

    @Test fun cancelPathPublishesSessionCancelledThenStateChanged() = runTest {
        val sid = SessionId("s1")
        val store = newStore()
        seedAssistantMessage(store, "s1", "a1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        // Launch the collector on backgroundScope — runTest
        // auto-cancels it at test end so we don't leak.
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob()).also {
            it.currentAssistantId = MessageId("a1")
        }
        finalizeCancelledRun(
            store = store,
            bus = bus,
            sessionId = sid,
            handle = handle,
            cause = CancellationException("user-cancel"),
        )
        advanceUntilIdle()
        yield()

        // Pin: cancel-path publishes BOTH SessionCancelled
        // AND AgentRunStateChanged(Cancelled), in that order.
        val targets = collected.filter {
            (it is BusEvent.SessionCancelled && it.sessionId == sid) ||
                (it is BusEvent.AgentRunStateChanged && it.sessionId == sid)
        }
        assertEquals(2, targets.size, "exactly 2 target events; got: $targets")
        assertTrue(
            targets[0] is BusEvent.SessionCancelled,
            "SessionCancelled first; got: ${targets[0]}",
        )
        val terminal = targets[1] as BusEvent.AgentRunStateChanged
        assertEquals(AgentRunState.Cancelled, terminal.state)
    }

    @Test fun cancelPathStampsAssistantMessageThroughInnerFinalizer() = runTest {
        val store = newStore()
        seedAssistantMessage(store, "s1", "a1")
        val bus = EventBus()
        backgroundScope.launch { bus.events.collect {} }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob()).also {
            it.currentAssistantId = MessageId("a1")
        }
        finalizeCancelledRun(
            store = store,
            bus = bus,
            sessionId = SessionId("s1"),
            handle = handle,
            cause = CancellationException("aborted"),
        )
        advanceUntilIdle()
        yield()

        // Pin: the wrapper delegates to finalizeCancelled,
        // which stamps the assistant message FinishReason
        // = CANCELLED.
        val msg = store.getMessage(MessageId("a1")) as Message.Assistant
        assertEquals(FinishReason.CANCELLED, msg.finish)
        assertEquals("aborted", msg.error)
    }

    @Test fun cancelPathCarriesLastRetryAttemptIntoTerminalState() = runTest {
        val store = newStore()
        seedAssistantMessage(store, "s1", "a1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob()).also {
            it.currentAssistantId = MessageId("a1")
            it.lastRetryAttempt = 3 // 3 retries before cancel
        }
        finalizeCancelledRun(
            store = store,
            bus = bus,
            sessionId = SessionId("s1"),
            handle = handle,
            cause = CancellationException("cancel"),
        )
        advanceUntilIdle()
        yield()

        val terminal = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .first { it.sessionId == SessionId("s1") }
        assertEquals(3, terminal.retryAttempt, "lastRetryAttempt threaded through")
    }

    @Test fun cancelPathTolerableNullAssistantId() = runTest {
        // Pin: when no assistant message was spawned before
        // cancel, the inner finalizeCancelled is a no-op and
        // the bus emits proceed normally — no NPE.
        val store = newStore()
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob()) // no assistant id
        finalizeCancelledRun(
            store = store,
            bus = bus,
            sessionId = SessionId("s1"),
            handle = handle,
            cause = CancellationException("cancel"),
        )
        advanceUntilIdle()
        yield()

        // Pin: bus events still publish even with null
        // assistant id; no crash.
        val cancelled = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .firstOrNull { it.sessionId == SessionId("s1") && it.state == AgentRunState.Cancelled }
        assertNotNull(cancelled, "Cancelled state-change still published")
    }

    // ── error path: single emission with message-fallback chain ─

    @Test fun errorPathPublishesSingleStateChangedFailedWithMessage() = runTest {
        val sid = SessionId("s1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob())
        finalizeFailedRun(
            bus = bus,
            sessionId = sid,
            handle = handle,
            cause = RuntimeException("provider 503"),
        )
        advanceUntilIdle()
        yield()

        val terminal = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .first { it.sessionId == sid }
        val failed = terminal.state as AgentRunState.Failed
        assertEquals("provider 503", failed.cause, "throwable.message used as cause")

        // Pin: error path publishes EXACTLY ONE
        // AgentRunStateChanged for the target session.
        val targetCount = collected.count {
            it is BusEvent.AgentRunStateChanged && it.sessionId == sid
        }
        assertEquals(1, targetCount, "exactly one state-change emit on error path")
        // Pin: NO SessionCancelled on error path.
        val cancelCount = collected.count {
            it is BusEvent.SessionCancelled && it.sessionId == sid
        }
        assertEquals(0, cancelCount, "error path doesn't emit SessionCancelled")
    }

    @Test fun errorPathFallsBackToClassNameWhenMessageNull() = runTest {
        val sid = SessionId("s1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob())
        // RuntimeException() has null message.
        finalizeFailedRun(
            bus = bus,
            sessionId = sid,
            handle = handle,
            cause = RuntimeException(),
        )
        advanceUntilIdle()
        yield()

        val terminal = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .first { it.sessionId == sid }
        val failed = terminal.state as AgentRunState.Failed
        assertEquals(
            "RuntimeException",
            failed.cause,
            "fallback to simpleName when message is null",
        )
    }

    @Test fun errorPathCarriesLastRetryAttemptIntoFailedState() = runTest {
        val sid = SessionId("s1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob()).also {
            it.lastRetryAttempt = 5 // 5 retries before final failure
        }
        finalizeFailedRun(
            bus = bus,
            sessionId = sid,
            handle = handle,
            cause = RuntimeException("rate limit"),
        )
        advanceUntilIdle()
        yield()

        val terminal = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .first { it.sessionId == sid }
        assertEquals(5, terminal.retryAttempt)
    }

    @Test fun errorPathDoesNotRequireStoreParameter() = runTest {
        // Pin: error-path is bus-only — the throwable will be
        // rethrown by the caller and the store-side cleanup
        // happens elsewhere. This pins the no-store signature.
        val bus = EventBus()
        backgroundScope.launch { bus.events.collect {} }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob())
        // No store seeded; finalize must not require one.
        finalizeFailedRun(
            bus = bus,
            sessionId = SessionId("s1"),
            handle = handle,
            cause = RuntimeException("err"),
        )
        advanceUntilIdle()
        yield()
        // If this compiles + runs, the no-store signature
        // contract holds.
    }

    // ── fallback-hint classification ───────────────────────────

    @Test fun errorPathClassifiesFallbackHintFromThrowable() = runTest {
        val sid = SessionId("s1")
        val bus = EventBus()
        val collected = mutableListOf<BusEvent>()
        backgroundScope.launch { bus.events.collect { collected += it } }
        advanceUntilIdle()
        yield()

        val handle = AgentRunHandle(SupervisorJob())
        finalizeFailedRun(
            bus = bus,
            sessionId = sid,
            handle = handle,
            cause = RuntimeException("something obscure"),
        )
        advanceUntilIdle()
        yield()

        val terminal = collected.filterIsInstance<BusEvent.AgentRunStateChanged>()
            .first { it.sessionId == sid }
        val failed = terminal.state as AgentRunState.Failed
        // Pin: fallback bucket is non-null AND classified.
        assertNotNull(failed.fallback, "fallback hint populated from classifier")
    }
}
