package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row for `select=cost_history` — one entry per AIGC dispatch that recorded a
 * `costCents` on the project's lockfile.
 *
 * Provider-lane counterpart of the `aigc.cost.*` accumulators in
 * [io.talevia.core.metrics.MetricsRegistry] and the per-provider rollup in
 * `session_query(select=spend_summary)`. Those answer "how much have I spent"
 * at increasing granularities; this answers "what were my last N AIGC calls"
 * — the streaming view the agent / operator actually wants when triaging a
 * cost spike or eyeballing recent activity across projects.
 *
 * Sourced from every project's `lockfile.entries`, sorted by
 * `provenance.createdAtEpochMs` descending, and capped at the caller's `limit`
 * (default 50, max 500). Entries with `costCents == null` are filtered out —
 * they represent legacy entries or non-priced tool calls (zero-cost stubs)
 * that would just be noise in a cost ledger.
 */
@Serializable
data class CostHistoryRow(
    val toolId: String,
    val providerId: String,
    val modelId: String,
    val costCents: Long,
    val projectId: String,
    val sessionId: String?,
    val originatingMessageId: String?,
    val assetId: String,
    val createdAtEpochMs: Long,
)

/**
 * `select=cost_history` — most-recent N priced AIGC dispatches across every
 * project in the [ProjectStore].
 *
 * Cost is sourced from `LockfileEntry.costCents`; entries without a price
 * (legacy or unpriced tools) are skipped so the ledger stays clean. Sort key
 * is `provenance.createdAtEpochMs desc` — ties broken by stable iteration
 * order (which inside a single project's lockfile equals append order, so
 * "most recently added" wins).
 *
 * Why scan every project rather than the current one only: AIGC cost is a
 * cross-project concern. A user with three concurrent edits wants "what did
 * the last hour cost me" without having to re-issue the query per project.
 * The per-project view is already covered by
 * `project_query(select=lockfile_entries, sinceEpochMs=…)`.
 *
 * The `sinceEpochMs` param trims at the source (per project) before the
 * cross-project merge so a tight time window stays cheap on stores with many
 * projects. `limit` is post-merge — it caps the final returned rows.
 */
internal suspend fun runCostHistoryQuery(
    store: ProjectStore,
    limit: Int,
    sinceEpochMs: Long?,
): ToolResult<ProviderQueryTool.Output> {
    val projects = store.list()

    val rows = projects.asSequence()
        .flatMap { project ->
            val pid = project.id.value
            project.lockfile.stream().mapNotNull { entry ->
                val cents = entry.costCents ?: return@mapNotNull null
                val createdAt = entry.provenance.createdAtEpochMs
                if (sinceEpochMs != null && createdAt < sinceEpochMs) return@mapNotNull null
                CostHistoryRow(
                    toolId = entry.toolId,
                    providerId = entry.provenance.providerId,
                    modelId = entry.provenance.modelId,
                    costCents = cents,
                    projectId = pid,
                    sessionId = entry.sessionId,
                    originatingMessageId = entry.originatingMessageId?.value,
                    assetId = entry.assetId.value,
                    createdAtEpochMs = createdAt,
                )
            }
        }
        .sortedByDescending { it.createdAtEpochMs }
        .toList()

    val totalMatching = rows.size
    val capped = rows.take(limit)

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(CostHistoryRow.serializer()),
        capped,
    ) as JsonArray

    val totalCents = capped.sumOf { it.costCents }
    val summary = if (capped.isEmpty()) {
        val window = sinceEpochMs?.let { " (sinceEpochMs=$it)" } ?: ""
        "No priced AIGC dispatches found across ${projects.size} project${if (projects.size == 1) "" else "s"}$window."
    } else {
        val previewN = minOf(5, capped.size)
        val preview = capped.take(previewN).joinToString("; ") {
            "${it.toolId}/${it.modelId} ${it.costCents}¢"
        }
        val ellipsis = if (capped.size > previewN) "; …" else ""
        "${capped.size} of $totalMatching priced dispatches across ${projects.size} project${if (projects.size == 1) "" else "s"}, " +
            "totaling ${totalCents}¢. Most recent: $preview$ellipsis"
    }

    return ToolResult(
        title = "provider_query cost_history (${capped.size}/$totalMatching)",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_COST_HISTORY,
            total = totalMatching,
            returned = capped.size,
            rows = jsonRows,
        ),
    )
}
