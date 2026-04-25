package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.provider.RateLimitHistoryRecorder
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * One per-provider summary row for `select=rate_limit_history` — count
 * of rate-limited retry events captured + their wallclock window +
 * the most recent reason string. Sourced from
 * [RateLimitHistoryRecorder]; entries are ring-buffered (cap 256 per
 * provider) and process-scoped (no cross-restart persistence).
 *
 * `count` is the in-buffer sample size, NOT a lifetime total. A
 * provider that hit the rate limit 1000 times since process start
 * shows count=256 once the ring fills; the buffer keeps the most
 * recent. That matches the operator question — "what's been
 * happening recently" — better than a lifetime tally that drifts
 * stale across long-running processes.
 */
@Serializable
data class RateLimitHistoryRow(
    val providerId: String,
    val count: Int,
    /** Earliest captured event in the buffer (ring-buffer head). */
    val firstEpochMs: Long,
    /** Most recent captured event. Null when count == 0 (shouldn't happen — empty buckets are filtered out). */
    val lastEpochMs: Long,
    /** Sum of waitMs across the window — total time the agent spent backing off for this provider. */
    val totalWaitMs: Long,
    /** Most-recent reason string captured. Useful for "what was the last 429 message?" debug. */
    val mostRecentReason: String,
)

/**
 * `select=rate_limit_history` — per-provider rate-limit retry
 * summary.
 *
 * Backstory: HTTP 429 / `x-ratelimit-remaining` retries fire silently
 * inside the retry loop and only surface as a backoff line in the CLI
 * log. Ops dashboards / cost-aware operators want "how often am I
 * hitting Anthropic's tier-1 limit today?" without tailing the bus.
 * This select aggregates the same data the
 * [RateLimitHistoryRecorder] has been capturing since container
 * bootstrap and surfaces it as one summary row per provider.
 *
 * No filters — returns one row per provider that has had ≥1 rate-
 * limit hit since the recorder attached. Empty rows on a fresh
 * process. When the recorder isn't wired (test rigs without the
 * aggregator), the query reports an empty result with a "recorder
 * not wired" note rather than failing — same convention as
 * `warmup_stats` / `permission_history`.
 *
 * Sorted by providerId for stable diffing across calls.
 */
internal fun runRateLimitHistoryQuery(
    recorder: RateLimitHistoryRecorder?,
): ToolResult<ProviderQueryTool.Output> {
    val snapshot = recorder?.snapshot() ?: emptyMap()
    val rows = snapshot.entries
        .filter { it.value.isNotEmpty() }
        .map { (providerId, entries) ->
            RateLimitHistoryRow(
                providerId = providerId,
                count = entries.size,
                firstEpochMs = entries.first().epochMs,
                lastEpochMs = entries.last().epochMs,
                totalWaitMs = entries.sumOf { it.waitMs },
                mostRecentReason = entries.last().reason,
            )
        }
        .sortedBy { it.providerId }

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(RateLimitHistoryRow.serializer()),
        rows,
    ) as JsonArray

    val summary = when {
        recorder == null ->
            "Rate-limit history recorder not wired in this rig — query reports zero rows. " +
                "(Production containers always wire it; this only fires in non-recorder test rigs.)"
        rows.isEmpty() ->
            "No rate-limit retries captured since process start across any wired provider."
        else -> "Rate-limit retries on ${rows.size} provider${if (rows.size == 1) "" else "s"}: " +
            rows.joinToString(", ") { "${it.providerId}=${it.count}" }
    }

    return ToolResult(
        title = "provider_query rate_limit_history (${rows.size} provider${if (rows.size == 1) "" else "s"})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_RATE_LIMIT_HISTORY,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
