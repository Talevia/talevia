package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [Agent] retries on transient provider errors (HTTP 5xx, 429,
 * overload / rate-limit text) and gives up on terminal ones. Mirrors OpenCode's
 * `session/retry.ts` behaviour — the first-tier guarantee is that a temporary
 * provider hiccup doesn't surface as a broken turn in the transcript.
 */
class AgentRetryTest {

    @Test
    fun transient5xxRetriesThenSucceeds() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error(
                message = "anthropic HTTP 503: overloaded_error: please retry",
                retriable = true,
            ),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val partId = PartId("ok-1")
        val success = listOf(
            LlmEvent.TextStart(partId),
            LlmEvent.TextDelta(partId, "hello"),
            LlmEvent.TextEnd(partId, "hello"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 3, output = 1)),
        )

        val retryEvents = mutableListOf<BusEvent.AgentRetryScheduled>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectorJob = collectorScope.launch {
            bus.events.filterIsInstance<BusEvent.AgentRetryScheduled>().collect { retryEvents += it }
        }
        // Let the subscribe land before we run the agent.
        yield()

        val provider = FakeProvider(listOf(failing, success))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            // Zero wait so the test doesn't burn real wall-clock seconds —
            // the runTest scheduler would skip real delays anyway, but being
            // explicit also keeps this robust if we ever change schedulers.
            retryPolicy = RetryPolicy(maxAttempts = 4, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
        )

        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(2, provider.requests.size, "agent should have streamed twice")

        // The transcript should contain exactly ONE assistant message — the
        // failed attempt's row is deleted on retry.
        val messages = store.listMessagesWithParts(sessionId).filter { it.message is Message.Assistant }
        assertEquals(1, messages.size, "retry must delete the failed assistant message")
        val texts = messages.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        assertTrue(texts.contains("hello"), "retry success text should persist")

        // Let the collector drain pending emissions before we check.
        yield()
        collectorJob.cancel()
        collectorScope.cancel()
        assertEquals(1, retryEvents.size)
        assertEquals(1, retryEvents.single().attempt)
    }

    @Test
    fun nonRetriableErrorFailsImmediately() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error(
                message = "anthropic HTTP 400: invalid_request_error: bad schema",
                retriable = false,
            ),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val provider = FakeProvider(listOf(failing))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 4, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
        )

        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish)
        assertNotNull(asst.error)
        assertEquals(1, provider.requests.size, "4xx must not retry")
    }

    @Test
    fun retryExhaustsAfterMaxAttempts() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error(message = "openai HTTP 503: service unavailable", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val provider = FakeProvider(List(3) { failing })
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
        )

        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish)
        assertEquals(3, provider.requests.size, "3 total attempts = 1 initial + 2 retries")
    }

    @Test
    fun classifierRecognisesCommonRetryableStrings() {
        assertNotNull(RetryClassifier.reason("anthropic HTTP 503: overloaded_error", false))
        assertNotNull(RetryClassifier.reason("openai HTTP 429: rate_limit_exceeded", false))
        assertNotNull(RetryClassifier.reason("gemini: RESOURCE_EXHAUSTED: quota", false))
        assertNotNull(RetryClassifier.reason("Too Many Requests", false))
        assertNotNull(RetryClassifier.reason("service unavailable", false))

        // Explicit retriable hint always wins on retriable errors.
        assertNotNull(RetryClassifier.reason("some weird backend error", true))

        // Terminal.
        assertNull(RetryClassifier.reason("anthropic HTTP 400: invalid_request_error", false))
        assertNull(RetryClassifier.reason("context_length_exceeded", false))
        assertNull(RetryClassifier.reason("token limit exceeded", true), "overflow beats retriable hint")
    }

    @Test
    fun delayHonorsRetryAfterHeader() {
        // Zero jitter so the assertion can compare exact values — the jitter
        // behaviour itself is covered by RetryPolicyTest.
        val policy = RetryPolicy(
            initialDelayMs = 2_000,
            maxDelayNoHeadersMs = 10_000,
            jitterFactor = 0.0,
            rateLimitMinDelayMs = null,
        )
        assertEquals(5_000L, policy.delayFor(attempt = 1, retryAfterMs = 5_000))
        assertEquals(2_000L, policy.delayFor(attempt = 1))
        assertEquals(4_000L, policy.delayFor(attempt = 2))
        // Capped by maxDelayNoHeadersMs when no header.
        assertEquals(10_000L, policy.delayFor(attempt = 10))
        // Capped by global maxDelayMs when header says absurd value.
        val capped = RetryPolicy(maxDelayMs = 1_000, jitterFactor = 0.0)
        assertEquals(1_000L, capped.delayFor(attempt = 1, retryAfterMs = 60_000))
    }

    @Test
    fun noneDisablesRetry() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error(message = "HTTP 503 overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val provider = FakeProvider(listOf(failing))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy.None,
        )
        agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(1, provider.requests.size, "RetryPolicy.None must not retry")
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("retry-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "retry",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
