package io.talevia.core.metrics

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metrics sink is the one place downstream dashboards look to answer
 * "what is the agent doing, and is any of it failing?". A miscounted event or
 * a missing case would silently blind operators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricsTest {

    private val sessionId = SessionId("s-1")

    @Test fun incrementsAccumulate() = runTest {
        val r = MetricsRegistry()
        r.increment("foo")
        r.increment("foo", by = 2L)
        r.increment("bar")
        assertEquals(3L, r.get("foo"))
        assertEquals(1L, r.get("bar"))
        assertEquals(mapOf("foo" to 3L, "bar" to 1L), r.snapshot())
    }

    @Test fun snapshotIsDefensiveCopy() = runTest {
        val r = MetricsRegistry()
        r.increment("foo")
        val snap = r.snapshot()
        r.increment("foo")
        assertEquals(mapOf("foo" to 1L), snap)
        assertEquals(2L, r.get("foo"))
    }

    @Test fun resetClearsEverything() = runTest {
        val r = MetricsRegistry()
        r.increment("foo"); r.increment("bar")
        r.reset()
        assertTrue(r.snapshot().isEmpty())
    }

    @Test fun getReturnsZeroForUnknown() = runTest {
        assertEquals(0L, MetricsRegistry().get("nope"))
    }

    @Test fun sinkCountsEachBusEventOnce() = runTest {
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        // Let the sink's collector reach its suspension point before publishing —
        // SharedFlow with replay=0 drops emissions that arrive before any
        // subscriber is active.
        runCurrent()

        bus.publish(BusEvent.SessionCreated(sessionId))
        bus.publish(BusEvent.SessionUpdated(sessionId))
        bus.publish(BusEvent.AgentRunFailed(sessionId, "cid-1", "boom"))
        bus.publish(BusEvent.AgentRunFailed(sessionId, "cid-2", "boom"))
        advanceUntilIdle()

        assertEquals(2L, registry.get("agent.run.failed"))
        assertEquals(1L, registry.get("session.created"))
        assertEquals(1L, registry.get("session.updated"))

        job.cancel()
    }

    @Test fun permissionRepliedSplitsOnAccepted() = runTest {
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()

        bus.publish(BusEvent.PermissionReplied(sessionId, "r1", accepted = true, remembered = false))
        bus.publish(BusEvent.PermissionReplied(sessionId, "r2", accepted = false, remembered = false))
        bus.publish(BusEvent.PermissionReplied(sessionId, "r3", accepted = true, remembered = true))
        advanceUntilIdle()

        assertEquals(2L, registry.get("permission.granted"))
        assertEquals(1L, registry.get("permission.rejected"))
        job.cancel()
    }

    @Test fun agentRetrySplitsByReasonSlug() = runTest {
        // Regression guard for the per-reason counter split: ops dashboards
        // need to distinguish overload / rate_limit / http_5xx / http_429 /
        // quota / unavailable / other so an operator can tell a provider
        // outage from user-driven rate-limiting. Summing agent.retry.* still
        // yields the total retry count.
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()

        // Cover every slug category once, plus an "other" fallback.
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 1, waitMs = 100, reason = "Provider is overloaded"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 2, waitMs = 200, reason = "Rate limited"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 3, waitMs = 300, reason = "too many requests"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 4, waitMs = 400, reason = "anthropic HTTP 503: overloaded_error"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 5, waitMs = 500, reason = "openai HTTP 429: rate_limit"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 6, waitMs = 600, reason = "Provider quota exhausted"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 7, waitMs = 700, reason = "Provider unavailable"))
        bus.publish(BusEvent.AgentRetryScheduled(sessionId, attempt = 8, waitMs = 800, reason = "completely opaque provider hiccup"))
        advanceUntilIdle()

        // HTTP transport category wins over semantic: 503 body mentions "overloaded"
        // but the 503 status is what ops needs to see.
        assertEquals(1L, registry.get("agent.retry.http_5xx"))
        assertEquals(1L, registry.get("agent.retry.http_429"))
        assertEquals(1L, registry.get("agent.retry.overload"))
        // Two rate-limit-family messages: "Rate limited" + "too many requests".
        assertEquals(2L, registry.get("agent.retry.rate_limit"))
        assertEquals(1L, registry.get("agent.retry.quota_exhausted"))
        assertEquals(1L, registry.get("agent.retry.unavailable"))
        assertEquals(1L, registry.get("agent.retry.other"))

        // The legacy "agent.retry.scheduled" counter no longer exists; summing the
        // family gives the total (8 retries).
        val total = registry.snapshot().entries
            .filter { it.key.startsWith("agent.retry.") }
            .sumOf { it.value }
        assertEquals(8L, total)

        job.cancel()
    }

    @Test fun aigcCacheProbeSplitsHitMissAndTrackPerTool() = runTest {
        // Base counters aigc.cache.{hits,misses}.total give dashboards the
        // headline ratio; per-tool aigc.cache.<tool>.{hit,miss}.total lets
        // operators drill down ("generate_image caches poorly → check seed
        // plumbing"). Both must fire on every probe.
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()

        bus.publish(BusEvent.AigcCacheProbe(toolId = "generate_image", hit = true))
        bus.publish(BusEvent.AigcCacheProbe(toolId = "generate_image", hit = true))
        bus.publish(BusEvent.AigcCacheProbe(toolId = "generate_image", hit = false))
        bus.publish(BusEvent.AigcCacheProbe(toolId = "synthesize_speech", hit = false))
        advanceUntilIdle()

        // Headline totals across all tools.
        assertEquals(2L, registry.get("aigc.cache.hits.total"))
        assertEquals(2L, registry.get("aigc.cache.misses.total"))

        // Per-tool breakdowns — an empty tool must stay zero so scrape consumers
        // don't confuse unused tools with mis-tagged events.
        assertEquals(2L, registry.get("aigc.cache.generate_image.hit.total"))
        assertEquals(1L, registry.get("aigc.cache.generate_image.miss.total"))
        assertEquals(0L, registry.get("aigc.cache.synthesize_speech.hit.total"))
        assertEquals(1L, registry.get("aigc.cache.synthesize_speech.miss.total"))
        assertEquals(0L, registry.get("aigc.cache.generate_video.hit.total"))

        job.cancel()
    }

    @Test fun publishBeforeSubscribeIsDropped() = runTest {
        // Regression guard: if the sink Job hasn't started collecting yet,
        // SharedFlow with replay=0 drops the emission. Callers must wait for
        // the subscription to be live before relying on counts.
        val bus = EventBus()
        val registry = MetricsRegistry()
        // Publish BEFORE subscriber is active.
        bus.publish(BusEvent.SessionCreated(sessionId))
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()
        advanceUntilIdle()
        assertEquals(0L, registry.get("session.created"))
        job.cancel()
    }
}
