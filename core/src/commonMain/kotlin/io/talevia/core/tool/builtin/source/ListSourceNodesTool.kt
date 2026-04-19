package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
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
 * Read the project's structured Source — every node grouped by kind, with a
 * one-line summary the LLM can quote back when reasoning about which bindings to
 * apply. Optional `kind` / `kindPrefix` filter narrows the result so the agent
 * doesn't drag every node into context for a small lookup.
 *
 * Read-only and permission-trivial — registered with `source.read` defaulted to
 * ALLOW so the agent can orient itself in any session.
 */
class ListSourceNodesTool(
    private val projects: ProjectStore,
) : Tool<ListSourceNodesTool.Input, ListSourceNodesTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val kind: String? = null,
        val kindPrefix: String? = null,
        val includeBody: Boolean = false,
    )

    @Serializable data class NodeSummary(
        val id: String,
        val kind: String,
        val revision: Long,
        val contentHash: String,
        val parentIds: List<String>,
        val summary: String,
        val body: kotlinx.serialization.json.JsonElement? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val sourceRevision: Long,
        val totalCount: Int,
        val returnedCount: Int,
        val nodes: List<NodeSummary>,
    )

    override val id: String = "list_source_nodes"
    override val helpText: String =
        "List source nodes for a project (filterable by kind / kindPrefix). " +
            "Returns each node's id, kind, contentHash, parents, and a short human-readable summary. " +
            "Set includeBody=true to fetch the full JSON body — otherwise the summary is enough for binding decisions."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("kind") {
                put("type", "string")
                put("description", "Exact kind filter (e.g. core.consistency.character_ref).")
            }
            putJsonObject("kindPrefix") {
                put("type", "string")
                put("description", "Prefix filter (e.g. core.consistency. matches all consistency kinds).")
            }
            putJsonObject("includeBody") {
                put("type", "boolean")
                put("description", "Include each node's full JSON body in the response. Default false.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val all = project.source.nodes
        val filtered = all.filter { node ->
            (input.kind == null || node.kind == input.kind) &&
                (input.kindPrefix == null || node.kind.startsWith(input.kindPrefix))
        }
        val summaries = filtered.map { it.toSummary(input.includeBody) }
        val out = Output(
            projectId = input.projectId,
            sourceRevision = project.source.revision,
            totalCount = all.size,
            returnedCount = summaries.size,
            nodes = summaries,
        )
        val tail = if (summaries.isEmpty()) "no matches"
        else summaries.joinToString("\n") { "- ${it.id} (${it.kind}): ${it.summary}" }
        return ToolResult(
            title = "list source nodes (${summaries.size}/${all.size})",
            outputForLlm = "Source revision ${project.source.revision}, ${all.size} total node(s), " +
                "${summaries.size} returned.\n$tail",
            data = out,
        )
    }

    private fun SourceNode.toSummary(includeBody: Boolean): NodeSummary = NodeSummary(
        id = id.value,
        kind = kind,
        revision = revision,
        contentHash = contentHash,
        parentIds = parents.map { it.nodeId.value },
        summary = humanSummary(),
        body = if (includeBody) body else null,
    )

    private fun SourceNode.humanSummary(): String {
        asCharacterRef()?.let { return "name=${it.name}; ${it.visualDescription.take(80)}" }
        asStyleBible()?.let { return "name=${it.name}; ${it.description.take(80)}" }
        asBrandPalette()?.let { return "name=${it.name}; ${it.hexColors.size} color(s)" }
        // Generic fallback — top-level keys + first short string
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
