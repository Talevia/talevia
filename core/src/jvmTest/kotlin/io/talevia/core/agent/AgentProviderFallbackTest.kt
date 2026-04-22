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
import io.talevia.core.session.ModelRef
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
import kotlin.test.assertTrue

/**
 * Verifies Agent's provider-chain fallback: when the primary provider
 * exhausts its retry budget with zero content emitted, the Agent advances
 * to the next provider in `fallbackProviders`. Partner to [AgentRetryTest]
 * — retry handles same-provider transients, fallback handles cross-provider
 * outages.
 */
class AgentProviderFallbackTest {

    @Test
    fun exhaustedPrimaryFallsThroughToSecondary() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        // Primary: fails twice retriably (retry budget = 2).
        val primaryFail = listOf(
            LlmEvent.Error("primary HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(listOf(primaryFail, primaryFail), id = "primary")
        // Secondary: succeeds on first try.
        val partId = PartId("ok-1")
        val secondary = FakeProvider(
            listOf(
                listOf(
                    LlmEvent.TextStart(partId),
                    LlmEvent.TextDelta(partId, "fallback success"),
                    LlmEvent.TextEnd(partId, "fallback success"),
                    LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 2)),
                ),
            ),
            id = "secondary",
        )

        val fallbackEvents = mutableListOf<BusEvent.AgentProviderFallback>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            bus.events.filterIsInstance<BusEvent.AgentProviderFallback>().collect { fallbackEvents += it }
        }
        yield()

        val agent = Agent(
            provider = primary,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )

        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.END_TURN, asst.finish)
        // Primary got 2 attempts (retry budget), secondary got 1 (success).
        assertEquals(2, primary.requests.size, "primary should have exhausted retries")
        assertEquals(1, secondary.requests.size, "secondary should have been tried once")

        yield()
        job.cancel()
        scope.cancel()
        assertEquals(1, fallbackEvents.size, "exactly one fallback advance")
        val evt = fallbackEvents.single()
        assertEquals("primary", evt.fromProviderId)
        assertEquals("secondary", evt.toProviderId)
    }

    @Test
    fun emptyFallbackListPreservesRetryOnlyBehavior() = runTest {
        // Regression guard: the default (empty fallbackProviders) must behave
        // identically to pre-fallback Agent. The existing AgentRetryTest
        // scenarios stay passing; this is a belt-and-braces check for the
        // "no chain" branch.
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val primaryFail = listOf(
            LlmEvent.Error("primary HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(List(3) { primaryFail }, id = "primary")

        val fallbackEvents = mutableListOf<BusEvent.AgentProviderFallback>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            bus.events.filterIsInstance<BusEvent.AgentProviderFallback>().collect { fallbackEvents += it }
        }
        yield()

        val agent = Agent(
            provider = primary,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            // fallbackProviders defaults to emptyList()
        )

        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish)
        assertEquals(3, primary.requests.size)
        yield()
        job.cancel()
        scope.cancel()
        assertTrue(fallbackEvents.isEmpty(), "no fallback event without chain")
    }

    @Test
    fun midStreamFailurePreservesPartialDoesNotFallback() = runTest {
        // When primary has streamed content before failing (rare but real),
        // the Agent MUST NOT fall through to secondary — otherwise the user
        // sees two half-outputs stitched. Existing `emittedContent` guard
        // handles retry; same guard applies to fallback.
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val partId = PartId("partial")
        val primaryPartial = listOf(
            LlmEvent.TextStart(partId),
            LlmEvent.TextDelta(partId, "partial content"),
            LlmEvent.Error("primary HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(listOf(primaryPartial), id = "primary")
        val secondary = FakeProvider(emptyList(), id = "secondary")

        val fallbackEvents = mutableListOf<BusEvent.AgentProviderFallback>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            bus.events.filterIsInstance<BusEvent.AgentProviderFallback>().collect { fallbackEvents += it }
        }
        yield()

        val agent = Agent(
            provider = primary,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )
        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))

        assertEquals(FinishReason.ERROR, asst.finish)
        assertEquals(1, primary.requests.size, "primary streamed once (content emitted)")
        assertEquals(0, secondary.requests.size, "secondary must not be invoked on mid-stream failure")
        yield()
        job.cancel()
        scope.cancel()
        assertTrue(fallbackEvents.isEmpty(), "no fallback when content was already delivered")
    }

    @Test
    fun allProvidersFailingSurfacesTerminalError() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error("HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(List(2) { failing }, id = "primary")
        val secondary = FakeProvider(List(2) { failing }, id = "secondary")

        val agent = Agent(
            provider = primary,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )
        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish)
        assertEquals(2, primary.requests.size)
        assertEquals(2, secondary.requests.size, "secondary gets a full fresh retry budget")
    }

    @Test
    fun fallbackProviderWithSameIdAsPrimaryIsDeduplicated() = runTest {
        // The Agent dedupes by `provider.id` — passing primary again as a
        // fallback shouldn't give it a second shot.
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val failing = listOf(
            LlmEvent.Error("HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(List(2) { failing }, id = "same")

        val agent = Agent(
            provider = primary,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(primary), // same id — must be deduped
        )
        val asst = agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish)
        assertEquals(2, primary.requests.size, "same-id fallback must not produce extra attempts")
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("fallback-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "fallback",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
