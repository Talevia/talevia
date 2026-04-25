package io.talevia.core.tool.builtin.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.provider.RateLimitHistoryRecorder
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.RateLimitHistoryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `provider_query(select=rate_limit_history)` — the
 * per-provider rate-limit retry summary built off
 * [BusEvent.AgentRetryScheduled] events filtered by the
 * RetryClassifier's [io.talevia.core.agent.BackoffKind.RATE_LIMIT]
 * verdict.
 */
class ProviderQueryRateLimitHistoryTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun emptyRegistry(): ProviderRegistry =
        ProviderRegistry(byId = emptyMap(), default = null)

    @Test fun emptyRecorderReturnsZeroRows(): TestResult = runTest {
        val tool = ProviderQueryTool(
            emptyRegistry(),
            ProviderWarmupStats.withSupervisor(EventBus()),
            ProjectStoreTestKit.create(),
            rateLimitHistory = RateLimitHistoryRecorder.withSupervisor(EventBus()),
        )
        val out = tool.execute(
            ProviderQueryTool.Input(select = "rate_limit_history"),
            ctx(),
        ).data
        assertEquals(0, out.total)
    }

    @Test fun rateLimitedRetryEventsAggregateByProvider(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            recorder.awaitReady()

            // Two anthropic 429 events, one openai 429, one anthropic 503
            // (server kind, NOT rate-limit). Recorder should bucket
            // anthropic=2, openai=1; the 503 is dropped.
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 200,
                    reason = "anthropic HTTP 429: tier-1 RPM exceeded",
                    providerId = "anthropic",
                ),
            )
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 2,
                    waitMs = 400,
                    reason = "anthropic HTTP 429: still throttled",
                    providerId = "anthropic",
                ),
            )
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s2"),
                    attempt = 1,
                    waitMs = 100,
                    reason = "openai HTTP 429: usage cap",
                    providerId = "openai",
                ),
            )
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s3"),
                    attempt = 1,
                    waitMs = 50,
                    reason = "anthropic HTTP 503: overloaded_error",
                    providerId = "anthropic",
                ),
            )

            withTimeout(5.seconds) {
                while (
                    recorder.snapshot("anthropic").size < 2 ||
                    recorder.snapshot("openai").size < 1
                    ) yield()
            }

            val tool = ProviderQueryTool(
                emptyRegistry(),
                ProviderWarmupStats.withSupervisor(EventBus()),
                ProjectStoreTestKit.create(),
                rateLimitHistory = recorder,
            )
            val out = tool.execute(
                ProviderQueryTool.Input(select = "rate_limit_history"),
                ctx(),
            ).data
            assertEquals(2, out.total, "two providers had rate-limit hits")
            val rows = out.rows.decodeRowsAs(RateLimitHistoryRow.serializer())
            val byProvider = rows.associateBy { it.providerId }
            assertEquals(2, byProvider["anthropic"]?.count, "503 must NOT count as rate-limit")
            assertEquals(600L, byProvider["anthropic"]?.totalWaitMs, "anthropic backoffs sum (200 + 400)")
            assertEquals(1, byProvider["openai"]?.count)
            assertTrue(
                byProvider["anthropic"]?.mostRecentReason?.contains("still throttled") == true,
                "mostRecentReason carries the latest captured reason verbatim",
            )
        }
    }

    @Test fun providerIdRequiredForCaptureNullDropsEvent(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            recorder.awaitReady()
            // Legacy emitter without providerId — dropped.
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 100,
                    reason = "Rate limited",
                    providerId = null,
                ),
            )
            // Yield twice to give the collector a chance to drop the event.
            yield()
            yield()
            assertTrue(recorder.snapshot().isEmpty(), "null providerId must drop the event")
        }
    }

    @Test fun unwiredRecorderReportsZeroRowsWithNote(): TestResult = runTest {
        val tool = ProviderQueryTool(
            emptyRegistry(),
            ProviderWarmupStats.withSupervisor(EventBus()),
            ProjectStoreTestKit.create(),
            // rateLimitHistory deliberately omitted (defaults to null).
        )
        val result = tool.execute(
            ProviderQueryTool.Input(select = "rate_limit_history"),
            ctx(),
        )
        assertEquals(0, result.data.total)
        assertTrue("expected 'not wired' note: ${result.outputForLlm}") {
            result.outputForLlm.contains("not wired")
        }
    }
}
