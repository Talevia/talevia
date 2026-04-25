package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Serialize a source node and all its parents into a portable JSON envelope
 * (VISION §5.1 "Source 能不能序列化、版本化、跨 project 复用?").
 *
 * `source_node_action(action="import")` with the live
 * `(fromProjectId, fromNodeId)` shape already covers **intra-instance**
 * sharing — copy a character_ref from one project to another inside the
 * same Talevia DB. But it requires both projects to be open in the same
 * store, so it doesn't help the user who wants to:
 *   - back up a hand-tuned Mei character_ref to a file,
 *   - share a style_bible with a collaborator running their own Talevia,
 *   - check a brand_palette into version control alongside brand assets,
 *   - ship a pre-baked "ad template" as a portable artifact.
 *
 * This tool emits a JSON envelope that round-trips via
 * `source_node_action(action="import", envelope=…)` with content-addressed
 * dedup preserved. The envelope is **not** a snapshot of the entire project
 * — just the leaf node + its parent chain, topologically ordered (parents
 * first). That's the same minimal-reuse-unit the live import path operates
 * on, keeping semantics consistent between the two import shapes.
 *
 * The agent typically pairs this with `write_file` to land the envelope somewhere
 * durable, or returns the JSON in its reply for the user to copy/paste. The
 * `formatVersion` field is intentional: older importers reject unknown versions
 * loudly so we can evolve the schema without silent corruption.
 */
class ExportSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<ExportSourceNodeTool.Input, ExportSourceNodeTool.Output> {

    private val json: Json get() = JsonConfig.default

    @Serializable data class Input(
        val projectId: String,
        /** The leaf node to export. Parents are walked automatically. */
        val nodeId: String,
        /**
         * When true, pretty-print the serialized envelope (human-friendly,
         * ~20% larger). Default false — compact JSON is cheaper to stash in
         * memory / a DB column and round-trips identically on import.
         */
        val prettyPrint: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val nodeId: String,
        val nodeCount: Int,
        val kinds: List<String>,
        /** The serialized envelope as a JSON string, ready to hand to `write_file`
         *  or feed directly to `source_node_action(action="import", envelope=…)`. */
        val envelope: String,
    )

    override val id: String = "export_source_node"
    override val helpText: String =
        "Serialize a source node plus every parent it references into a portable JSON envelope. " +
            "Use for cross-instance share / backup / version control — the envelope round-trips " +
            "via source_node_action(action=import, envelope=…) with content-addressed dedup preserved. " +
            "For intra-instance copies between two local projects use " +
            "source_node_action(action=import, fromProjectId=…, fromNodeId=…) instead (no file hop " +
            "needed). Emits a formatVersion so future schema changes are caught."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") { put("type", "string") }
            putJsonObject("prettyPrint") {
                put("type", "boolean")
                put("description", "Pretty-print the envelope. Default false (compact).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val leafId = SourceNodeId(input.nodeId)
        val byId = project.source.nodes.associateBy { it.id }
        require(leafId in byId) {
            "Source node ${input.nodeId} not found in project ${input.projectId}."
        }
        val ordered = topoCollect(byId, leafId)

        val envelope = SourceNodeEnvelope(
            formatVersion = FORMAT_VERSION,
            rootNodeId = leafId.value,
            nodes = ordered.map { node ->
                ExportedNode(
                    id = node.id.value,
                    kind = node.kind,
                    body = node.body,
                    parents = node.parents.map { it.nodeId.value },
                )
            },
        )
        val jsonInstance = if (input.prettyPrint) {
            Json(from = json) { prettyPrint = true }
        } else {
            json
        }
        val serialized = jsonInstance.encodeToString(SourceNodeEnvelope.serializer(), envelope)

        val kinds = ordered.map { it.kind }.distinct()
        val kindsLabel = if (kinds.size == 1) kinds.single() else "${kinds.size} kinds"
        val parentsNote = if (ordered.size > 1) " with ${ordered.size - 1} parent node(s)" else ""
        return ToolResult(
            title = "export ${ordered.last().kind} ${leafId.value}",
            outputForLlm = "Exported ${leafId.value} from ${pid.value}$parentsNote as " +
                "$FORMAT_VERSION ($kindsLabel, ${serialized.length} bytes). Pass data.envelope to " +
                "write_file to persist, or to source_node_action(action=import, envelope=…) " +
                "on another project.",
            data = Output(
                projectId = pid.value,
                nodeId = leafId.value,
                nodeCount = ordered.size,
                kinds = kinds,
                envelope = serialized,
            ),
        )
    }

    /**
     * Walk [leafId] and every transitive parent in topological (parents-first) order,
     * the same traversal [topoCollectForLiveImport] uses on the live cross-project
     * import path. Fails loudly on a dangling SourceRef — an incomplete export
     * would emit a payload the importer can't apply.
     */
    private fun topoCollect(byId: Map<SourceNodeId, SourceNode>, leafId: SourceNodeId): List<SourceNode> {
        val visited = mutableSetOf<SourceNodeId>()
        val result = mutableListOf<SourceNode>()
        fun visit(id: SourceNodeId) {
            if (id in visited) return
            visited += id
            val node = byId[id] ?: error(
                "Source node ${id.value} referenced as parent but not present in the project.",
            )
            for (parent in node.parents) visit(parent.nodeId)
            result += node
        }
        visit(leafId)
        return result
    }

    companion object {
        /**
         * Schema identifier embedded in every envelope. Bumped on breaking changes so
         * importers can refuse unknown versions loudly — the alternative (silent
         * tolerance) risks corrupting the target project when field semantics evolve.
         */
        const val FORMAT_VERSION: String = "talevia-source-export-v1"
    }
}

/**
 * On-the-wire envelope for [ExportSourceNodeTool]. Stable JSON shape — additive
 * changes only; breaking changes bump [ExportSourceNodeTool.FORMAT_VERSION].
 */
@Serializable
data class SourceNodeEnvelope(
    val formatVersion: String,
    val rootNodeId: String,
    val nodes: List<ExportedNode>,
)

@Serializable
data class ExportedNode(
    val id: String,
    val kind: String,
    val body: JsonElement = JsonObject(emptyMap()),
    val parents: List<String> = emptyList(),
)
