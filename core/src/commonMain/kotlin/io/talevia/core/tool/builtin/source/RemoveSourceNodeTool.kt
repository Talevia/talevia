package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.removeNode
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
 * Remove a source node by id. Does not cascade — clips that referenced this node
 * via `sourceBinding` will read it as missing on the next stale check (treated
 * as always-stale per [io.talevia.core.domain.staleClips]). Cleaning up orphan
 * bindings is the agent's job after the user confirms the removal was intended.
 *
 * Permission: `source.write` (same scope as the create / update tools).
 */
class RemoveSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<RemoveSourceNodeTool.Input, RemoveSourceNodeTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
    )

    @Serializable data class Output(
        val nodeId: String,
        val removedKind: String,
    )

    override val id: String = "remove_source_node"
    override val helpText: String =
        "Remove a source node by id. Does not cascade — downstream clips with a binding to this node " +
            "will be treated as stale on the next render. Use source_query(select=nodes) first to confirm the id."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

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
        val pid = ProjectId(input.projectId)
        val nodeId = SourceNodeId(input.nodeId)
        var removedKind = ""
        projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error("Source node ${input.nodeId} not found in project ${input.projectId}")
            removedKind = existing.kind
            source.removeNode(nodeId)
        }
        val out = Output(input.nodeId, removedKind)
        return ToolResult(
            title = "remove source node ${input.nodeId}",
            outputForLlm = "Removed $removedKind node ${input.nodeId}. " +
                "Clips that bound this id will be re-rendered next export.",
            data = out,
        )
    }
}
