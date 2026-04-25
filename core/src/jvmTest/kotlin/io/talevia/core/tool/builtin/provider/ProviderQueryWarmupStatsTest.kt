package io.talevia.core.tool.builtin.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.WarmupStatsRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `select=warmup_stats` on [ProviderQueryTool] and the underlying
 * [ProviderWarmupStats] aggregator. Pair semantics live in the aggregator
 * (matched Starting→Ready becomes a sample; orphan Starting is silently
 * dropped; orphan Ready is a no-op); the tool-side path is a thin wrapper
 * so the bulk of assertions target both layers in one fixture.
 */
class ProviderQueryWarmupStatsTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)

    @AfterTest fun teardown() {
        job.cancel()
    }

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

    private fun bus(): EventBus = EventBus()

    @Test fun emptyStatsReturnsZeroRows() = runTest {
        val stats = ProviderWarmupStats(bus(), scope)
        val out = ProviderQueryTool(emptyRegistry(), stats, io.talevia.core.domain.ProjectStoreTestKit.create()).execute(
            ProviderQueryTool.Input(select = "warmup_stats"),
            ctx(),
        ).data
        assertEquals("warmup_stats", out.select)
        assertEquals(0, out.total)
        val rows = out.rows.decodeRowsAs(WarmupStatsRow.serializer())
        assertEquals(emptyList(), rows)
    }

    @Test fun matchedPairBecomesSampleRow() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope)
        eb.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId("s"),
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Starting,
                epochMs = 1_000,
            ),
        )
        yield()
        eb.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId("s"),
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Ready,
                epochMs = 3_500,
            ),
        )
        yield()

        val out = ProviderQueryTool(emptyRegistry(), stats, io.talevia.core.domain.ProjectStoreTestKit.create()).execute(
            ProviderQueryTool.Input(select = "warmup_stats"),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(WarmupStatsRow.serializer())
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("replicate", row.providerId)
        assertEquals(1L, row.count)
        assertEquals(2_500L, row.p50Ms)
        assertEquals(2_500L, row.p99Ms)
        assertEquals(2_500L, row.minMs)
        assertEquals(2_500L, row.maxMs)
        assertEquals(2_500L, row.latestMs)
    }

    @Test fun orphanStartingWithoutReadyIsIgnored() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope)
        eb.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId("s"),
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Starting,
                epochMs = 1_000,
            ),
        )
        // Give the collector a turn — no Ready event means no sample observed.
        yield()
        assertEquals(emptyMap(), stats.samples.value)
    }

    @Test fun orphanReadyWithoutStartingIsDropped() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope)
        eb.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId("s"),
                providerId = "replicate",
                phase = BusEvent.ProviderWarmup.Phase.Ready,
                epochMs = 1_500,
            ),
        )
        yield()
        assertEquals(emptyMap(), stats.samples.value)
    }

    @Test fun twoProvidersYieldTwoRowsSortedById() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope)
        for ((providerId, delta) in listOf("replicate" to 800L, "anthropic" to 2_000L)) {
            eb.publish(
                BusEvent.ProviderWarmup(SessionId("s"), providerId, BusEvent.ProviderWarmup.Phase.Starting, epochMs = 0),
            )
            yield()
            eb.publish(
                BusEvent.ProviderWarmup(SessionId("s"), providerId, BusEvent.ProviderWarmup.Phase.Ready, epochMs = delta),
            )
            yield()
        }

        val rows = ProviderQueryTool(emptyRegistry(), stats, io.talevia.core.domain.ProjectStoreTestKit.create()).execute(
            ProviderQueryTool.Input(select = "warmup_stats"),
            ctx(),
        ).data.rows.decodeRowsAs(WarmupStatsRow.serializer())
        assertEquals(listOf("anthropic", "replicate"), rows.map { it.providerId }, "sorted by providerId")
        assertEquals(2_000L, rows.first { it.providerId == "anthropic" }.latestMs)
        assertEquals(800L, rows.first { it.providerId == "replicate" }.latestMs)
    }

    @Test fun ringBufferCapsAtCapacity() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope, capacity = 3)
        // Push 5 pairs; oldest 2 drop out.
        for (i in 1..5) {
            eb.publish(
                BusEvent.ProviderWarmup(SessionId("s$i"), "replicate", BusEvent.ProviderWarmup.Phase.Starting, epochMs = i * 1_000L),
            )
            yield()
            eb.publish(
                BusEvent.ProviderWarmup(SessionId("s$i"), "replicate", BusEvent.ProviderWarmup.Phase.Ready, epochMs = i * 1_000L + i * 100L),
            )
            yield()
        }

        val rows = ProviderQueryTool(emptyRegistry(), stats, io.talevia.core.domain.ProjectStoreTestKit.create()).execute(
            ProviderQueryTool.Input(select = "warmup_stats"),
            ctx(),
        ).data.rows.decodeRowsAs(WarmupStatsRow.serializer())
        val row = rows.single()
        assertEquals(3L, row.count, "capacity=3 means only last 3 samples retained")
        // Last three deltas are 300ms, 400ms, 500ms.
        assertEquals(500L, row.latestMs)
        assertEquals(300L, row.minMs)
        assertEquals(500L, row.maxMs)
    }

    @Test fun providerIdFilterRejected() = runTest {
        val eb = bus()
        val stats = ProviderWarmupStats(eb, scope)
        val ex = assertFailsWith<IllegalStateException> {
            ProviderQueryTool(emptyRegistry(), stats, io.talevia.core.domain.ProjectStoreTestKit.create()).execute(
                ProviderQueryTool.Input(select = "warmup_stats", providerId = "replicate"),
                ctx(),
            )
        }
        assertTrue("providerId" in ex.message!!, "providerId filter must be rejected: ${ex.message}")
    }
}
