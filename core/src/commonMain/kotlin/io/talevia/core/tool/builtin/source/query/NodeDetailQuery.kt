package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.childIndex
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class NodeDetailParentRef(
    val nodeId: String,
    /** Parent's kind, or `"(missing)"` if the parent id does not resolve in the DAG. */
    val kind: String,
)

@Serializable
data class NodeDetailChildRef(
    val nodeId: String,
    val kind: String,
)

@Serializable
data class NodeDetailBoundClip(
    val clipId: String,
    val trackId: String,
    val assetId: String? = null,
    /** True when this node appears directly in the clip's `sourceBinding`. */
    val directly: Boolean,
    /** The subset of the clip's binding that lies in this node's transitive closure. */
    val boundViaNodeIds: List<String>,
)

/**
 * `select=node_detail` — single-row deep zoom on one Source DAG node.
 *
 * Cycle 137 absorbed the standalone `describe_source_node` tool into
 * `source_query` (mirroring the earlier `describe_clip` →
 * `project_query(select=clip_detail)` and `describe_lockfile_entry` →
 * `project_query(select=lockfile_entry_detail)` folds). Single-entity
 * "tell me everything about *this one node*" queries belong on the same
 * dispatcher as bulk projections — keeping a separate top-level tool
 * was an unnecessary tax on every LLM turn's tool-spec budget.
 *
 * Behaviour byte-identical to the pre-fold tool: typed body, parents
 * with kinds resolved, direct DAG children, every clip whose
 * `sourceBinding` intersects this node's transitive-downstream closure
 * (with `directly` flag), and a one-line humanised summary in
 * `outputForLlm`.
 */
@Serializable
data class NodeDetailRow(
    val nodeId: String,
    val kind: String,
    val revision: Long,
    val contentHash: String,
    val body: JsonElement,
    val parentRefs: List<NodeDetailParentRef>,
    val children: List<NodeDetailChildRef>,
    val boundClips: List<NodeDetailBoundClip>,
    /**
     * One-line humanised summary (name + clip-description for typed
     * consistency nodes, key list for opaque bodies). Same humaniser
     * the pre-fold `describe_source_node` tool emitted.
     */
    val summary: String,
)

internal fun runNodeDetailQuery(
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> {
    val idValue = input.id
        ?: error(
            "select='${SourceQueryTool.SELECT_NODE_DETAIL}' requires id (the node to describe). " +
                "Call source_query(select=nodes) to discover valid ids.",
        )
    val nodeId = SourceNodeId(idValue)
    val node = project.source.byId[nodeId]
        ?: error(
            "Source node $idValue not found in project ${project.id.value}. " +
                "Call source_query(select=nodes) to discover valid ids.",
        )

    val byId = project.source.byId
    val parents = node.parents.map { ref ->
        NodeDetailParentRef(
            nodeId = ref.nodeId.value,
            kind = byId[ref.nodeId]?.kind ?: "(missing)",
        )
    }

    val children = project.source.childIndex[nodeId].orEmpty()
        .mapNotNull { childId ->
            byId[childId]?.let { NodeDetailChildRef(nodeId = childId.value, kind = it.kind) }
        }
        .sortedBy { it.nodeId }

    val boundClips = project.clipsBoundTo(nodeId).map { report ->
        NodeDetailBoundClip(
            clipId = report.clipId.value,
            trackId = report.trackId.value,
            assetId = report.assetId?.value,
            directly = report.directlyBound,
            boundViaNodeIds = report.boundVia.map { it.value }.sorted(),
        )
    }

    val row = NodeDetailRow(
        nodeId = node.id.value,
        kind = node.kind,
        revision = node.revision,
        contentHash = node.contentHash,
        body = node.body,
        parentRefs = parents,
        children = children,
        boundClips = boundClips,
        summary = node.humanSummary(),
    )

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(NodeDetailRow.serializer()),
        listOf(row),
    ) as JsonArray

    val parentsLine = if (parents.isEmpty()) "none" else parents.joinToString(", ") {
        "${it.nodeId}(${it.kind})"
    }
    val childrenLine = if (children.isEmpty()) "none" else children.joinToString(", ") {
        "${it.nodeId}(${it.kind})"
    }
    val directCount = boundClips.count { it.directly }
    val transCount = boundClips.size - directCount
    val boundLine = when {
        boundClips.isEmpty() -> "no clips bound"
        transCount == 0 -> "$directCount clip(s) bound directly"
        else -> "$directCount direct + $transCount transitive clip(s) bound"
    }

    return ToolResult(
        title = "source_query node_detail ${node.kind} $idValue",
        outputForLlm = buildString {
            append("${node.kind} $idValue (rev=${node.revision}, hash=${node.contentHash.take(8)}): ")
            append(row.summary)
            append("\n- parents: $parentsLine")
            append("\n- children: $childrenLine")
            append("\n- $boundLine")
        },
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_NODE_DETAIL,
            total = 1,
            returned = 1,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

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
