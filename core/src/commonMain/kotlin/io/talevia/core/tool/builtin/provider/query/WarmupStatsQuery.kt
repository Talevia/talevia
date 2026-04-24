package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row for `select=warmup_stats` — one summary per provider that has had at
 * least one matched Starting→Ready pair since the process started. All
 * latencies are in milliseconds.
 *
 * [count] is the sample-window size (≤ `ProviderWarmupStats.capacity`), not
 * the lifetime observation count. Same retention semantics as
 * [io.talevia.core.metrics.MetricsRegistry.histogramSnapshot] — recent
 * behaviour is what the agent / operator actually wants to see.
 */
@Serializable
data class WarmupStatsRow(
    val providerId: String,
    val count: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val minMs: Long,
    val maxMs: Long,
    val latestMs: Long,
)

/**
 * `select=warmup_stats` — per-provider cold-start latency picture.
 *
 * Data source is the long-lived [ProviderWarmupStats] aggregator wired into
 * the container's composition root; it pairs `BusEvent.ProviderWarmup`
 * Starting / Ready emissions and maintains a rolling window.
 *
 * Providers that have never emitted a matched pair (e.g. OpenAI's
 * synchronous `/v1/images/generations` — no separate warmup/streaming
 * split) are absent from the result. An agent that wants to distinguish
 * "no warmup signal yet" from "provider doesn't emit warmup" should
 * cross-reference `select=providers`.
 */
internal fun runWarmupStatsQuery(
    warmupStats: ProviderWarmupStats,
): ToolResult<ProviderQueryTool.Output> {
    val snapshot = warmupStats.snapshot()
    val rows = snapshot.entries
        .map { (providerId, snap) ->
            WarmupStatsRow(
                providerId = providerId,
                count = snap.count,
                p50Ms = snap.p50Ms,
                p95Ms = snap.p95Ms,
                p99Ms = snap.p99Ms,
                minMs = snap.minMs,
                maxMs = snap.maxMs,
                latestMs = snap.latestMs,
            )
        }
        .sortedBy { it.providerId }

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(WarmupStatsRow.serializer()),
        rows,
    ) as JsonArray

    val summary = if (rows.isEmpty()) {
        "No provider warmup samples yet — no Starting→Ready pairs observed " +
            "since the process started. Providers with synchronous endpoints " +
            "(e.g. OpenAI image/video) intentionally emit nothing."
    } else {
        "Warmup latency over ${rows.size} provider${if (rows.size == 1) "" else "s"}: " +
            rows.joinToString(", ") {
                "${it.providerId} p50=${it.p50Ms}ms p99=${it.p99Ms}ms (${it.count} sample${if (it.count == 1L) "" else "s"})"
            }
    }

    return ToolResult(
        title = "provider_query warmup_stats (${rows.size} provider${if (rows.size == 1) "" else "s"})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_WARMUP_STATS,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
