package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Single-row payload for `select=ascii_tree`. [tree] is a pretty-printed
 * ASCII projection of the Source DAG for direct terminal viewing;
 * [nodeCount] / [edgeCount] / [rootCount] echo top-line counts so
 * consumers can branch without re-parsing.
 *
 * Complements [DotRow] (same DAG, different output format): DOT for
 * Graphviz rendering, ASCII for quick terminal peeks. Choice of format
 * is per-call, not a Project-level setting.
 */
@Serializable
data class AsciiTreeRow(
    val tree: String,
    val nodeCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
)

/**
 * `select=ascii_tree` — render the project's Source DAG as an indented
 * ASCII tree. Each line starts with box-drawing characters (`├─`, `└─`,
 * `│`) followed by `<nodeId> (<kind>)` and an `[orphan]` marker for
 * nodes no clip binds to. Roots (nodes with no parent) are emitted
 * at the left margin in sorted order; descendants indent under each
 * parent.
 *
 * Diamond cases (one node reached from two parents) are handled by
 * printing the node under each parent's branch with a `(dup)` marker on
 * repeat mentions so the output stays a tree (not a DAG), matching
 * typical `tree` / `ls -R` behaviour. The `dup` marker keeps the line
 * count tied to edge count + root count rather than node count —
 * consumers that care about unique-node count read [AsciiTreeRow.nodeCount].
 */
internal fun runAsciiTreeQuery(
    project: Project,
): ToolResult<SourceQueryTool.Output> {
    val nodes = project.source.nodes
    val byId: Map<SourceNodeId, SourceNode> = nodes.associateBy { it.id }
    val children: Map<SourceNodeId, List<SourceNodeId>> = buildMap {
        for (node in nodes) {
            for (parentRef in node.parents) {
                getOrPut(parentRef.nodeId) { mutableListOf() }.let { list ->
                    (list as MutableList).add(node.id)
                }
            }
        }
    }

    val allIds = nodes.map { it.id }.toSet()
    val childIdsAcrossParents = children.values.flatten().toSet()
    val roots = allIds
        .asSequence()
        .filter { id -> (byId[id]?.parents?.isEmpty() == true) || id !in childIdsAcrossParents }
        .sortedBy { it.value }
        .toList()

    val orphanSet = nodes
        .filter { project.clipsBoundTo(it.id).isEmpty() }
        .mapTo(HashSet()) { it.id }

    val sb = StringBuilder()
    if (roots.isEmpty()) {
        sb.append("(empty source DAG)\n")
    } else {
        // Track which node ids we've already printed so repeat mentions
        // under a second parent get a `(dup)` marker instead of expanding
        // their subtree twice — keeps the output linear in edge count.
        val printed = HashSet<SourceNodeId>()
        for ((index, rootId) in roots.withIndex()) {
            val isLast = index == roots.lastIndex
            renderNode(sb, byId, children, orphanSet, printed, rootId, prefix = "", isLast = isLast, isRoot = true)
        }
    }

    val nodeCount = nodes.size
    val edgeCount = nodes.sumOf { it.parents.size }
    val rootCount = roots.size
    val row = AsciiTreeRow(
        tree = sb.toString(),
        nodeCount = nodeCount,
        edgeCount = edgeCount,
        rootCount = rootCount,
    )
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(AsciiTreeRow.serializer()),
        listOf(row),
    ) as JsonArray

    return ToolResult(
        title = "source_query ascii_tree ($nodeCount node${if (nodeCount == 1) "" else "s"}, " +
            "$rootCount root${if (rootCount == 1) "" else "s"})",
        outputForLlm = "Source DAG for '${project.id.value}' as ASCII tree " +
            "($nodeCount nodes, $edgeCount edges, $rootCount roots). Read the `tree` " +
            "field for the indented projection; `(dup)` marks repeat mentions of " +
            "multi-parent nodes.",
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_ASCII_TREE,
            total = 1,
            returned = 1,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

private fun renderNode(
    sb: StringBuilder,
    byId: Map<SourceNodeId, SourceNode>,
    children: Map<SourceNodeId, List<SourceNodeId>>,
    orphanSet: Set<SourceNodeId>,
    printed: HashSet<SourceNodeId>,
    id: SourceNodeId,
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean,
) {
    val branchMarker = when {
        isRoot -> ""
        isLast -> "└─ "
        else -> "├─ "
    }
    val node = byId[id]
    val kind = node?.kind ?: "?"
    val orphanMark = if (id in orphanSet) " [orphan]" else ""
    val dupMark = if (!printed.add(id)) " (dup)" else ""
    sb.append(prefix).append(branchMarker).append(id.value)
        .append(" (").append(kind).append(")").append(orphanMark).append(dupMark).append("\n")

    // Stop descending on a repeat mention — the first print owned the
    // subtree expansion.
    if (dupMark.isNotEmpty()) return

    val childIds = children[id].orEmpty().sortedBy { it.value }
    val childPrefix = when {
        isRoot -> ""
        isLast -> "$prefix   "
        else -> "$prefix│  "
    }
    for ((index, childId) in childIds.withIndex()) {
        renderNode(
            sb = sb,
            byId = byId,
            children = children,
            orphanSet = orphanSet,
            printed = printed,
            id = childId,
            prefix = childPrefix,
            isLast = index == childIds.lastIndex,
            isRoot = false,
        )
    }
}
