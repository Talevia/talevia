package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.source.childIndex
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row for `select=leaves` — one DAG node with no children (no other
 * source node lists this one as a parent). Tip of a chain — the natural
 * "regenerate me" target after a parent edit propagates downstream, or
 * the natural place to hang a new derived node.
 *
 * Pairs with [OrphanRow] (zero clip-bound) and the implicit roots
 * (zero-parent nodes accessible via `select=nodes&hasParent=false`):
 * three positional axes covering the actionable extremes of a DAG.
 *
 * `parentCount` carries DAG-topology hint so callers can distinguish a
 * standalone leaf (parents=0, children=0 — also flagged by orphans
 * unless clip-bound) from a normal "tip of a chain" leaf with parents.
 */
@Serializable
data class LeafRow(
    val id: String,
    val kind: String,
    val revision: Long,
    val parentCount: Int,
)

/**
 * `select=leaves` — every DAG node no other node lists as a parent.
 * Roots-or-leaves filter on `select=nodes` already supports
 * `hasParent=false` for roots; this is its symmetric sibling for the
 * downstream tip. Returns rows sorted by id. No filters / pagination —
 * leaf sets are bounded by node count and stay small in practice.
 */
internal fun runLeavesQuery(
    project: Project,
): ToolResult<SourceQueryTool.Output> {
    val nodes = project.source.nodes
    val children = project.source.childIndex

    val leaves = nodes
        .filter { (children[it.id] ?: emptySet()).isEmpty() }
        .map { node ->
            LeafRow(
                id = node.id.value,
                kind = node.kind,
                revision = node.revision,
                parentCount = node.parents.size,
            )
        }
        .sortedBy { it.id }

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(LeafRow.serializer()),
        leaves,
    ) as JsonArray

    val title = "source_query leaves (${leaves.size} leaf node${if (leaves.size == 1) "" else "s"})"
    val outputForLlm = if (leaves.isEmpty()) {
        "No leaf source nodes in '${project.id.value}' — empty DAG."
    } else {
        "${leaves.size} leaf source node${if (leaves.size == 1) "" else "s"} in " +
            "'${project.id.value}' (no other node lists them as a parent). Read rows for " +
            "{id, kind, revision, parentCount}; parentCount=0 marks a standalone node " +
            "(check `select=orphans` for clip-binding status); parentCount > 0 marks " +
            "the downstream tip of a chain — natural regenerate-after-edit targets."
    }

    return ToolResult(
        title = title,
        outputForLlm = outputForLlm,
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_LEAVES,
            total = leaves.size,
            returned = leaves.size,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}
