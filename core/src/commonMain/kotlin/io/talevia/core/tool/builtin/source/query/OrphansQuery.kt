package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.childIndex
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row for `select=orphans` — one node that no clip (directly or transitively)
 * binds to. [parentCount] / [childCount] helps the caller tell a leaf stray
 * apart from the root of an orphan subtree, so cleanup workflows can pick
 * the right granularity (drop single node vs prune whole subtree).
 *
 * Same orphan semantics as [DagSummaryRow.orphanedNodeIds] and the `[orphan]`
 * marker in `ascii_tree` — a node is orphan iff `project.clipsBoundTo(id)` is
 * empty. Surfacing it as a first-class select avoids `nodes` + O(N) follow-up
 * `clips_for_source` crosschecks.
 */
@Serializable
data class OrphanRow(
    val id: String,
    val kind: String,
    val revision: Long,
    val parentCount: Int,
    val childCount: Int,
)

/**
 * `select=orphans` — enumerate every DAG node zero clip binds to. Returns
 * rows sorted by id. No filters / pagination (orphan sets are small in
 * practice — see `rejectIncompatibleFilters`).
 */
internal fun runOrphansQuery(
    project: Project,
): ToolResult<SourceQueryTool.Output> {
    val nodes = project.source.nodes
    val children = project.source.childIndex

    val orphans = nodes
        .filter { project.clipsBoundTo(it.id).isEmpty() }
        .map { node ->
            OrphanRow(
                id = node.id.value,
                kind = node.kind,
                revision = node.revision,
                parentCount = node.parents.size,
                childCount = (children[node.id] ?: emptySet()).size,
            )
        }
        .sortedBy { it.id }

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(OrphanRow.serializer()),
        orphans,
    ) as JsonArray

    val title = "source_query orphans (${orphans.size} orphan${if (orphans.size == 1) "" else "s"})"
    val outputForLlm = if (orphans.isEmpty()) {
        "No orphan source nodes in '${project.id.value}' — every DAG node has at least one clip binding (direct or transitive)."
    } else {
        "${orphans.size} orphan source node${if (orphans.size == 1) "" else "s"} in " +
            "'${project.id.value}' (no clip binds directly or transitively). Read rows " +
            "for {id, kind, revision, parentCount, childCount}; parentCount=0 + " +
            "childCount=0 marks a fully stray node, childCount > 0 marks the root of " +
            "an orphan subtree the caller may want to prune wholesale."
    }

    return ToolResult(
        title = title,
        outputForLlm = outputForLlm,
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_ORPHANS,
            total = orphans.size,
            returned = orphans.size,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}
