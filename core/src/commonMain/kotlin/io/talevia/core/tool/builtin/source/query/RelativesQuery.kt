package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.childIndex
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `select=descendants` — every [SourceNode] transitively reachable downstream
 * from [SourceQueryTool.Input.root] via the DAG's reverse-parent index. The
 * root itself is included as row 0 (`depthFromRoot=0`), immediate children
 * as depth 1, and so on. Cycle-safe via a visited set. Depth cap from
 * [SourceQueryTool.Input.depth] (null or negative = unbounded).
 */
internal fun runDescendantsQuery(
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> = runRelativesQuery(
    project = project,
    input = input,
    selectConst = SourceQueryTool.SELECT_DESCENDANTS,
    direction = "descendants",
    neighborsOf = { id, children, _ -> children[id].orEmpty() },
)

/**
 * `select=ancestors` — every [SourceNode] transitively upstream of
 * [SourceQueryTool.Input.root] via each node's `parents` list. Row shape +
 * depth semantics mirror [runDescendantsQuery].
 */
internal fun runAncestorsQuery(
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> = runRelativesQuery(
    project = project,
    input = input,
    selectConst = SourceQueryTool.SELECT_ANCESTORS,
    direction = "ancestors",
    neighborsOf = { id, _, byId -> byId[id]?.parents?.map { it.nodeId }?.toSet() ?: emptySet() },
)

private fun runRelativesQuery(
    project: Project,
    input: SourceQueryTool.Input,
    selectConst: String,
    direction: String,
    neighborsOf: (
        SourceNodeId,
        Map<SourceNodeId, Set<SourceNodeId>>,
        Map<SourceNodeId, SourceNode>,
    ) -> Set<SourceNodeId>,
): ToolResult<SourceQueryTool.Output> {
    val rootStr = input.root
        ?: error("select='$selectConst' requires root (a source node id). Call source_query(select=nodes) to discover valid ids.")
    val rootId = SourceNodeId(rootStr)
    val byId = project.source.byId
    val rootNode = byId[rootId]
        ?: error("Source node '$rootStr' not found in project ${project.id.value}. Call source_query(select=nodes) to discover valid ids.")

    val maxDepth = input.depth  // null or negative = unbounded
    val children = project.source.childIndex
    val includeBody = input.includeBody ?: false

    // BFS, recording the depth at which each node is first visited.
    val depthOf = LinkedHashMap<SourceNodeId, Int>()
    depthOf[rootId] = 0
    val queue: ArrayDeque<SourceNodeId> = ArrayDeque()
    queue.addLast(rootId)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val currentDepth = depthOf[current]!!
        if (maxDepth != null && maxDepth >= 0 && currentDepth >= maxDepth) continue
        for (next in neighborsOf(current, children, byId)) {
            if (next !in byId) continue  // dangling ref — drop silently, matches Source.stale semantics
            if (next !in depthOf) {
                depthOf[next] = currentDepth + 1
                queue.addLast(next)
            }
        }
    }

    val totalHits = depthOf.size
    val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
    val offset = (input.offset ?: 0).coerceAtLeast(0)
    val paged = depthOf.entries.drop(offset).take(limit)

    val rows: List<NodeRow> = paged.map { (nodeId, depth) ->
        val node = byId[nodeId]!!
        node.toRelativesRow(depth, includeBody)
    }
    val jsonRows: JsonArray = JsonConfig.default.encodeToJsonElement(
        ListSerializer(NodeRow.serializer()),
        rows,
    ) as JsonArray

    val depthLabel = if (maxDepth != null && maxDepth >= 0) "depth ≤ $maxDepth" else "unbounded"
    val summary = "Source revision ${project.source.revision}, $direction of ${rootId.value} " +
        "(${rootNode.kind}, $depthLabel): $totalHits reachable node(s)" +
        if (rows.size < totalHits) ", returning ${rows.size} (offset $offset, limit $limit)." else "."
    val body = if (rows.isEmpty()) "" else "\n" + rows.joinToString("\n") { r ->
        val d = r.depthFromRoot?.let { " +$it" } ?: ""
        "- ${r.id}$d (${r.kind}): ${r.summary}"
    }
    return ToolResult(
        title = "source_query $direction ${rootId.value} (${rows.size}/$totalHits)",
        outputForLlm = summary + body,
        data = SourceQueryTool.Output(
            select = selectConst,
            total = totalHits,
            returned = rows.size,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

private fun SourceNode.toRelativesRow(
    depthFromRoot: Int,
    includeBody: Boolean,
): NodeRow = NodeRow(
    id = id.value,
    kind = kind,
    revision = revision,
    contentHash = contentHash,
    parentIds = parents.map { it.nodeId.value },
    summary = humanSummary(),
    body = if (includeBody) body else null,
    depthFromRoot = depthFromRoot,
)

private fun SourceNode.humanSummary(): String {
    asCharacterRef()?.let { return "name=${it.name}; ${it.visualDescription.take(80)}" }
    asStyleBible()?.let { return "name=${it.name}; ${it.description.take(80)}" }
    asBrandPalette()?.let { return "name=${it.name}; ${it.hexColors.size} color(s)" }
    val obj = body as? JsonObject ?: return "(opaque body)"
    val keys = obj.keys.take(5).joinToString(",")
    val firstString = obj.values
        .filterIsInstance<JsonPrimitive>()
        .firstOrNull { it.isString }
        ?.content
        ?.take(60)
    return if (firstString != null) "keys={$keys}; $firstString" else "keys={$keys}"
}

private const val DEFAULT_LIMIT = 100
private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 500
