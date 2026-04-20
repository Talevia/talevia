package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.childIndex
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Compact bird's-eye summary of a project's Source DAG — roots, leaves, max depth,
 * per-kind counts, hotspots (nodes with many downstream clips) and orphans (nodes
 * bound by no clip). VISION §5.1 rubric asks whether there is an explicit
 * "structured source" concept and whether the cross-downstream-clip relationship is
 * visible. `list_source_nodes` + `list_clips_for_source` let the agent drill in;
 * this tool gives it the single read needed to *orient* before drilling.
 *
 * Traversal reuses [childIndex] from `core.domain.source.SourceDag` rather than
 * re-walking the graph locally. Cycle-safety is defensive (a valid Source DAG is
 * acyclic but we never validated that at the data layer).
 *
 * Read-only, permission `project.read`.
 */
class DescribeSourceDagTool(
    private val projects: ProjectStore,
) : Tool<DescribeSourceDagTool.Input, DescribeSourceDagTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val hotspotLimit: Int = 5,
    )

    @Serializable data class Hotspot(
        val nodeId: String,
        val kind: String,
        val directClipCount: Int,
        val transitiveClipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val nodeCount: Int,
        val nodesByKind: Map<String, Int>,
        val rootNodeIds: List<String>,
        val leafNodeIds: List<String>,
        val maxDepth: Int,
        val hotspots: List<Hotspot>,
        val orphanedNodeIds: List<String>,
        val summaryText: String,
    )

    override val id: String = "describe_source_dag"
    override val helpText: String =
        "Structural overview of a project's source DAG: total nodes, per-kind counts, roots " +
            "(no parents), leaves (no children), longest root→leaf chain, hotspots ranked by " +
            "downstream-clip count, and orphans (nodes bound by no clip). One read to orient " +
            "before editing a source node — drill in with list_source_nodes / list_clips_for_source."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("hotspotLimit") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Maximum number of hotspots to return. Default 5.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")
        val source = project.source
        val nodes = source.nodes

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

        // Orphans + clip counts — one pass through every node calling clipsBoundTo once each.
        // clipsBoundTo already does the transitive walk; that's the "transitive" count.
        // directClipCount filters to reports flagged `directlyBound`.
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

        val limit = input.hotspotLimit.coerceAtLeast(0)
        val hotspots = hotspotCandidates
            .sortedWith(
                compareByDescending<Hotspot> { it.transitiveClipCount }
                    .thenBy { it.nodeId },
            )
            .take(limit)

        val summaryText = buildSummary(
            projectId = pid.value,
            nodeCount = nodes.size,
            nodesByKind = nodesByKind,
            rootCount = rootIds.size,
            leafCount = leafIds.size,
            maxDepth = maxDepth,
            orphanCount = orphans.size,
            hotspots = hotspots,
        )

        val out = Output(
            projectId = pid.value,
            nodeCount = nodes.size,
            nodesByKind = nodesByKind,
            rootNodeIds = rootIds,
            leafNodeIds = leafIds,
            maxDepth = maxDepth,
            hotspots = hotspots,
            orphanedNodeIds = orphans.sorted(),
            summaryText = summaryText,
        )

        return ToolResult(
            title = "describe source dag (${nodes.size} node${if (nodes.size == 1) "" else "s"})",
            outputForLlm = summaryText,
            data = out,
        )
    }

    /**
     * Longest root→leaf chain in the DAG. Iterates every root and does a DFS with a
     * visited set (cycle-safety) bumping a local depth counter. Returns 0 for empty
     * graphs, 1 for a lone node.
     */
    private fun computeMaxDepth(
        source: Source,
        children: Map<SourceNodeId, Set<SourceNodeId>>,
    ): Int {
        if (source.nodes.isEmpty()) return 0
        val roots: List<SourceNodeId> = source.nodes
            .filter { it.parents.isEmpty() }
            .map { it.id }
        // If a graph somehow has only cycles and no roots, fall back to every node
        // as a potential start — still returns at least 1.
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
        val leavesPart = "$leafCount leaf${if (leafCount == 1) "" else "ves"}"
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
}
