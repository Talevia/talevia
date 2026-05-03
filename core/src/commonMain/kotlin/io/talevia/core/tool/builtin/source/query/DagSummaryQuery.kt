package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.childIndex
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class Hotspot(
    val nodeId: String,
    val kind: String,
    val directClipCount: Int,
    val transitiveClipCount: Int,
)

@Serializable
data class DagSummaryRow(
    val nodeCount: Int,
    val nodesByKind: Map<String, Int>,
    val rootNodeIds: List<String>,
    val leafNodeIds: List<String>,
    val maxDepth: Int,
    val hotspots: List<Hotspot>,
    val orphanedNodeIds: List<String>,
    val summaryText: String,
)

/**
 * `select=dag_summary` — one-row structural overview: per-kind counts, roots,
 * leaves, max depth, hotspots, orphans. Carried over verbatim from the
 * pre-merge `DescribeSourceDagTool` behavior.
 */
internal fun runDagSummaryQuery(
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> {
    val source = project.source
    val nodes = source.nodes
    val hotspotLimit = (input.hotspotLimit ?: DEFAULT_HOTSPOT_LIMIT).coerceAtLeast(0)

    val nodesByKind: Map<String, Int> = nodes
        .groupingBy { it.kind }
        .eachCount()
        .toList()
        .sortedBy { it.first }
        .toMap(LinkedHashMap())

    val children = source.childIndex
    val rootIds: List<String> = nodes
        .filter { it.parents.isEmpty() }
        .map { it.id.value }
        .sorted()
    val leafIds: List<String> = nodes
        .filter { (children[it.id] ?: emptySet()).isEmpty() }
        .map { it.id.value }
        .sorted()
    val maxDepth = computeMaxDepth(source, children)

    val orphans = mutableListOf<String>()
    val hotspotCandidates = mutableListOf<Hotspot>()
    for (node in nodes) {
        val reports = project.clipsBoundTo(node.id)
        if (reports.isEmpty()) {
            orphans += node.id.value
            continue
        }
        val direct = reports.count { it.directlyBound }
        hotspotCandidates += Hotspot(
            nodeId = node.id.value,
            kind = node.kind,
            directClipCount = direct,
            transitiveClipCount = reports.size,
        )
    }
    val hotspots = hotspotCandidates
        .sortedWith(
            compareByDescending<Hotspot> { it.transitiveClipCount }
                .thenBy { it.nodeId },
        )
        .take(hotspotLimit)

    val summaryText = buildSummary(
        projectId = project.id.value,
        nodeCount = nodes.size,
        nodesByKind = nodesByKind,
        rootCount = rootIds.size,
        leafCount = leafIds.size,
        maxDepth = maxDepth,
        orphanCount = orphans.size,
        hotspots = hotspots,
    )

    val row = DagSummaryRow(
        nodeCount = nodes.size,
        nodesByKind = nodesByKind,
        rootNodeIds = rootIds,
        leafNodeIds = leafIds,
        maxDepth = maxDepth,
        hotspots = hotspots,
        orphanedNodeIds = orphans.sorted(),
        summaryText = summaryText,
    )
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(DagSummaryRow.serializer()),
        listOf(row),
    ) as kotlinx.serialization.json.JsonArray

    return ToolResult(
        title = "source_query dag_summary (${nodes.size} node${if (nodes.size == 1) "" else "s"})",
        outputForLlm = summaryText,
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_DAG_SUMMARY,
            total = 1,
            returned = 1,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

private fun computeMaxDepth(
    source: Source,
    children: Map<SourceNodeId, Set<SourceNodeId>>,
): Int {
    if (source.nodes.isEmpty()) return 0
    val roots: List<SourceNodeId> = source.nodes
        .filter { it.parents.isEmpty() }
        .map { it.id }
    val starts = roots.ifEmpty { source.nodes.map { it.id } }
    var best = 1
    for (start in starts) {
        val depth = dfsDepth(start, children, HashSet())
        if (depth > best) best = depth
    }
    return best
}

private fun dfsDepth(
    node: SourceNodeId,
    children: Map<SourceNodeId, Set<SourceNodeId>>,
    visited: HashSet<SourceNodeId>,
): Int {
    if (!visited.add(node)) return 0
    val kids = children[node].orEmpty()
    var deepest = 0
    for (child in kids) {
        val childDepth = dfsDepth(child, children, visited)
        if (childDepth > deepest) deepest = childDepth
    }
    visited.remove(node)
    return 1 + deepest
}

private fun buildSummary(
    projectId: String,
    nodeCount: Int,
    nodesByKind: Map<String, Int>,
    rootCount: Int,
    leafCount: Int,
    maxDepth: Int,
    orphanCount: Int,
    hotspots: List<Hotspot>,
): String {
    if (nodeCount == 0) {
        return "Source DAG for '$projectId': 0 nodes (empty graph)."
    }
    val kindBreakdown = nodesByKind.entries.joinToString(", ") { (k, v) -> "$v $k" }
    val rootsPart = "$rootCount root${if (rootCount == 1) "" else "s"}"
    val leavesPart = "$leafCount ${if (leafCount == 1) "leaf" else "leaves"}"
    val hotspotPart = if (hotspots.isEmpty()) {
        ""
    } else {
        " Top hotspots: " + hotspots.joinToString(", ") {
            "${it.nodeId} → ${it.transitiveClipCount} clip${if (it.transitiveClipCount == 1) "" else "s"}"
        } + "."
    }
    val orphanPart = if (orphanCount == 0) "" else " $orphanCount orphaned node${if (orphanCount == 1) "" else "s"}."
    return "Source DAG for '$projectId': $nodeCount nodes ($kindBreakdown), " +
        "$rootsPart / $leavesPart, max depth $maxDepth.$orphanPart$hotspotPart"
}

private const val DEFAULT_HOTSPOT_LIMIT = 5
