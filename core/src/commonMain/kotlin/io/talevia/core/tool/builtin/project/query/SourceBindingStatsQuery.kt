package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Coverage row for `select=source_binding_stats` — one entry per
 * source-node `kind` present in the project. Surfaces the "is this
 * kind being used at all?" picture the agent needs when judging
 * whether a stylebible / character_ref / brand_palette is paying its
 * cost.
 *
 * - [totalNodes]: every node of this kind on the source DAG.
 * - [boundDirectly]: nodes whose id appears in some clip's
 *   `sourceBinding` directly.
 * - [boundTransitively]: nodes that aren't directly bound but have
 *   at least one descendant in the source DAG that IS directly
 *   bound. A node bound directly is NOT counted here (it's already in
 *   `boundDirectly`).
 * - [orphans]: nodes neither directly bound nor with any bound
 *   descendant. The "deletable / unused" candidates.
 *
 * `boundDirectly + boundTransitively + orphans == totalNodes` always
 * — the three categories partition the nodes of this kind.
 *
 * `coverageRatio` is `(boundDirectly + boundTransitively) / totalNodes`,
 * 0.0..1.0; surfaces "stylebible coverage 4/12 = 33%" without the
 * agent having to divide.
 */
@Serializable
data class SourceBindingStatsRow(
    val kind: String,
    val totalNodes: Int,
    val boundDirectly: Int,
    val boundTransitively: Int,
    val orphans: Int,
    val coverageRatio: Double,
    /** Sorted (ascending) ids of orphan nodes — small enough to surface inline at typical scale. */
    val orphanNodeIds: List<String>,
)

/**
 * `select=source_binding_stats` — per-kind coverage picture for the
 * project's source DAG.
 *
 * Backstory: `dag_summary` exposes `nodesByKind` as a flat count, but
 * "this kind has 12 nodes" doesn't tell the agent how many of them
 * are reaching a clip. The agent had to issue per-node
 * `consistency_propagation` queries to find out, which scales O(n)
 * with the DAG. This select aggregates the same data in one pass.
 *
 * Algorithm: for each node, [Project.clipsBoundTo] returns the union
 * of (this node, every descendant of this node) ∩ (every clip's
 * sourceBinding). Each report carries `directlyBound: Boolean` —
 * directlyBound=true means a clip lists this node's id, false means
 * the binding is via a descendant. We bucket each node into one of:
 * direct (≥1 directlyBound report), transitive (≥1 report but none
 * directly), orphan (no reports).
 *
 * Sorted by kind ascending for stable comparison across calls.
 */
internal fun runSourceBindingStatsQuery(
    project: Project,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    data class Bucket(
        var total: Int = 0,
        var direct: Int = 0,
        var transitive: Int = 0,
        val orphanIds: MutableList<String> = mutableListOf(),
    )

    val byKind = mutableMapOf<String, Bucket>()
    for (node in project.source.nodes) {
        val bucket = byKind.getOrPut(node.kind) { Bucket() }
        bucket.total += 1

        val reports = project.clipsBoundTo(node.id)
        when {
            reports.isEmpty() -> bucket.orphanIds += node.id.value
            reports.any { it.directlyBound } -> bucket.direct += 1
            else -> bucket.transitive += 1
        }
    }

    val rowsAll = byKind.entries
        .map { (kind, b) ->
            val orphans = b.orphanIds.size
            val covered = b.direct + b.transitive
            val coverage = if (b.total == 0) 0.0 else covered.toDouble() / b.total
            SourceBindingStatsRow(
                kind = kind,
                totalNodes = b.total,
                boundDirectly = b.direct,
                boundTransitively = b.transitive,
                orphans = orphans,
                coverageRatio = coverage,
                orphanNodeIds = b.orphanIds.sorted(),
            )
        }
        .sortedBy { it.kind }

    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)

    val jsonRows = encodeRows(ListSerializer(SourceBindingStatsRow.serializer()), rows)

    val narrative = if (rows.isEmpty()) {
        "Project ${project.id.value} has no source nodes."
    } else {
        // Surface the worst-coverage kind in the summary so the agent
        // notices low-utilisation kinds without having to scan rows.
        val worst = rowsAll.minByOrNull { it.coverageRatio }
        val totalNodes = rowsAll.sumOf { it.totalNodes }
        val totalOrphans = rowsAll.sumOf { it.orphans }
        val worstNote = worst?.let {
            " Lowest coverage: ${it.kind} ${it.boundDirectly + it.boundTransitively}/${it.totalNodes} " +
                "(${(it.coverageRatio * 100).toInt()}%)."
        } ?: ""
        "${rows.size} of $total kind(s) on project ${project.id.value}: $totalNodes nodes total, " +
            "$totalOrphans orphan(s).$worstNote"
    }

    return ToolResult(
        title = "project_query source_binding_stats (${rows.size}/$total)",
        outputForLlm = narrative,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_SOURCE_BINDING_STATS,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
