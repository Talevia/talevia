package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.childIndex
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
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
 * Focused single-node read — the missing "zoom in" tool for the Source DAG.
 *
 * `describe_source_dag` gives the bird's-eye structural summary. `list_source_nodes`
 * paginates rows. Neither answers the common agent question "tell me everything
 * about *this one node* so I can decide how to edit it": its typed body, its
 * parents and their kinds, its direct child nodes in the DAG, which clips on the
 * timeline it binds, and whether those clips are direct or transitively bound.
 *
 * Pulling this into `get_project_state` bloats every caller; pulling it per-field
 * (`list_source_nodes includeBody=true` for body, then `list_clips_for_source` for
 * bindings, then a third call to resolve parent kinds) is three round-trips.
 * This tool is the cheap single read.
 *
 * Output shape:
 *  - `node`: full identity + body + parent refs with their kinds resolved.
 *  - `children`: id + kind of every node listing this one in its `parents`.
 *  - `boundClips`: one row per clip whose `sourceBinding` intersects the
 *    transitive-downstream closure; flag `directly` for clips that name this
 *    node exactly in their binding, `false` for clips bound via a descendant.
 *  - `summary`: one-line humanised summary (reuses the same humaniser as
 *    `list_source_nodes`) for terse LLM readback.
 *
 * Read-only, `project.read` — cheap to call at any planning step.
 */
class DescribeSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<DescribeSourceNodeTool.Input, DescribeSourceNodeTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
    )

    @Serializable data class ParentRefInfo(
        val nodeId: String,
        /** Parent's kind, or "(missing)" if the parent id does not resolve in the DAG. */
        val kind: String,
    )

    @Serializable data class ChildRefInfo(
        val nodeId: String,
        val kind: String,
    )

    @Serializable data class BoundClipInfo(
        val clipId: String,
        val trackId: String,
        val assetId: String? = null,
        /** True when this node appears directly in the clip's `sourceBinding`. */
        val directly: Boolean,
        /** The subset of the clip's binding that lies in this node's transitive closure. */
        val boundViaNodeIds: List<String>,
    )

    @Serializable data class NodeDetails(
        val nodeId: String,
        val kind: String,
        val revision: Long,
        val contentHash: String,
        val body: kotlinx.serialization.json.JsonElement,
        val parentRefs: List<ParentRefInfo>,
    )

    @Serializable data class Output(
        val projectId: String,
        val node: NodeDetails,
        val children: List<ChildRefInfo>,
        val boundClips: List<BoundClipInfo>,
        val summary: String,
    )

    override val id: String = "describe_source_node"
    override val helpText: String =
        "One-shot zoom on a single source node: typed body, parents (with kinds resolved), " +
            "direct children in the DAG, every clip whose sourceBinding intersects this node's " +
            "transitive-downstream closure (with `directly` flag), and a one-line humanised " +
            "summary. Use this before editing a node to see exactly what it looks like and what " +
            "depends on it. Read-only; cheaper than get_project_state for single-node lookups."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val project = projects.get(ProjectId(input.projectId))
            ?: error("Project ${input.projectId} not found")
        val nodeId = SourceNodeId(input.nodeId)
        val node = project.source.byId[nodeId]
            ?: error(
                "Source node ${input.nodeId} not found in project ${input.projectId}. " +
                    "Call list_source_nodes to discover valid ids.",
            )

        val byId = project.source.byId
        val parents = node.parents.map { ref ->
            ParentRefInfo(
                nodeId = ref.nodeId.value,
                kind = byId[ref.nodeId]?.kind ?: "(missing)",
            )
        }

        val children = project.source.childIndex[nodeId].orEmpty()
            .mapNotNull { childId ->
                byId[childId]?.let { ChildRefInfo(nodeId = childId.value, kind = it.kind) }
            }
            .sortedBy { it.nodeId }

        val boundClips = project.clipsBoundTo(nodeId).map { report ->
            BoundClipInfo(
                clipId = report.clipId.value,
                trackId = report.trackId.value,
                assetId = report.assetId?.value,
                directly = report.directlyBound,
                boundViaNodeIds = report.boundVia.map { it.value }.sorted(),
            )
        }

        val out = Output(
            projectId = input.projectId,
            node = NodeDetails(
                nodeId = node.id.value,
                kind = node.kind,
                revision = node.revision,
                contentHash = node.contentHash,
                body = node.body,
                parentRefs = parents,
            ),
            children = children,
            boundClips = boundClips,
            summary = node.humanSummary(),
        )

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
            title = "describe ${node.kind} ${input.nodeId}",
            outputForLlm = buildString {
                append("${node.kind} ${input.nodeId} (rev=${node.revision}, hash=${node.contentHash.take(8)}): ")
                append(node.humanSummary())
                append("\n- parents: $parentsLine")
                append("\n- children: $childrenLine")
                append("\n- $boundLine")
            },
            data = out,
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
}
