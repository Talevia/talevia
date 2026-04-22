package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
 * Create a source node of any kind — the kind-agnostic counterpart to the
 * typed `set_character_ref` / `set_style_bible` / `set_brand_palette` trio
 * (which are upsert-with-patch for their three consistency kinds) and to
 * `update_source_node_body` (which only *edits* existing nodes).
 *
 * Motivation. `create_project_from_template` seeds a genre skeleton, and the
 * three `set_*` tools cover consistency nodes. But after bootstrap the
 * agent still cannot add a second `narrative.scene`, a fresh
 * `musicmv.performance_shot`, an extra `ad.variant_request`, etc. — none of
 * those genre kinds have dedicated `set_*` tools, and there's a design
 * choice not to mint 14+ per-kind wrappers (they'd each duplicate the same
 * JSON-schema / permission / commit shape with only the body serializer
 * differing). A single kind-agnostic `add_source_node` matches the shape of
 * `update_source_node_body` (opaque JSON body, kind-agnostic) and lets the
 * agent extend the DAG for any genre, including ones that ship after this
 * release — no Core change required.
 *
 * Contract with Core's genre layer. `SourceNode.kind` is a dotted-namespace
 * opaque string; Core never validates the body shape (that's the genre
 * layer's job via `asCharacterRef` / `asNarrativeScene` / …). We mirror that
 * discipline: we accept any non-blank kind plus any JSON body, trust the
 * caller to match them, and let downstream readers return `null` on
 * mismatch the same way they do for imported / hand-authored nodes today.
 *
 * Guardrails.
 *  - Reject a blank kind — a node with no kind can't be dispatched on.
 *  - Reject duplicate node id — same contract as `Source.addNode`; use
 *    `update_source_node_body` to edit an existing node.
 *  - Reject parent ids that don't exist in the project — a dangling
 *    `SourceRef` would silently break DAG propagation
 *    (`Source.stale` / `find_stale_clips`) and the staleness lane has no
 *    way to surface the mistake.
 *
 * Scope — creation only. Parents are set at construction for DAG propagation
 * correctness; mutations come from `update_source_node_body` (body),
 * `set_source_node_parents` (parents), and `rename_source_node` (id). Keeping
 * those verbs orthogonal matches the rest of the source-tools family.
 */
class AddSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<AddSourceNodeTool.Input, AddSourceNodeTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        /**
         * Dotted-namespace kind string, e.g. `"narrative.scene"`,
         * `"musicmv.performance_shot"`, `"ad.variant_request"`. Case-sensitive; matches
         * whatever the genre layer declared in its `*NodeKinds.kt`.
         */
        val kind: String,
        /** Opaque body. Must match the genre's expected shape — Core does not validate. */
        val body: JsonObject = JsonObject(emptyMap()),
        /** Optional parent node ids, each of which must already exist in the project. */
        val parentIds: List<String> = emptyList(),
    )

    @Serializable data class Output(
        val projectId: String,
        val nodeId: String,
        val kind: String,
        val contentHash: String,
        val parentIds: List<String>,
    )

    override val id: String = "add_source_node"
    override val helpText: String =
        "Create a source node of any kind with an opaque JSON body and optional parent ids. " +
            "Kind-agnostic counterpart to set_character_ref / set_style_bible / set_brand_palette " +
            "(which exist for ergonomic typed-body entry on those three consistency kinds). Use this " +
            "after create_project_from_template to extend the DAG with additional narrative.scene / " +
            "musicmv.performance_shot / ad.variant_request / tutorial.* nodes the template doesn't seed. " +
            "Rejects blank kinds, duplicate ids, and dangling parent ids. Edit the body later via " +
            "update_source_node_body; change parents via set_source_node_parents; rename via rename_source_node."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") { put("type", "string") }
            putJsonObject("kind") {
                put("type", "string")
                put(
                    "description",
                    "Dotted-namespace kind string (e.g. narrative.scene, musicmv.track, ad.variant_request, " +
                        "tutorial.script). Must match what the genre layer expects — Core does not validate.",
                )
            }
            putJsonObject("body") {
                put("type", "object")
                put(
                    "description",
                    "Opaque JSON body matching the genre's shape. Defaults to {}. Kind + body together " +
                        "drive the contentHash and thus downstream staleness.",
                )
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put(
                    "description",
                    "Optional parent node ids the new node depends on. Each must already exist in the " +
                        "project's source graph. Empty (default) means root node.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("nodeId"),
                    JsonPrimitive("kind"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.kind.isNotBlank()) { "kind must not be blank" }
        require(input.nodeId.isNotBlank()) { "nodeId must not be blank" }

        val pid = ProjectId(input.projectId)
        val newId = SourceNodeId(input.nodeId)
        val parentRefs = input.parentIds.map { SourceRef(SourceNodeId(it)) }

        var finalHash = ""
        projects.mutateSource(pid) { source ->
            require(newId !in source.byId) {
                "Source node ${input.nodeId} already exists in project ${input.projectId}. " +
                    "Use update_source_node_body to edit its body, or pick a fresh id."
            }
            val missing = parentRefs.map { it.nodeId }.filter { it !in source.byId }
            require(missing.isEmpty()) {
                "Parent node(s) not found in project ${input.projectId}: " +
                    "${missing.joinToString(", ") { it.value }}. Create them first or pass an empty parentIds."
            }
            val node = SourceNode.create(
                id = newId,
                kind = input.kind,
                body = input.body,
                parents = parentRefs,
            )
            val next = source.addNode(node)
            finalHash = next.byId[newId]!!.contentHash
            next
        }

        val parentNote = if (parentRefs.isEmpty()) "" else " parents=[${input.parentIds.joinToString(",")}]"
        return ToolResult(
            title = "add source ${input.kind} ${input.nodeId}",
            outputForLlm = "Added ${input.kind} node ${input.nodeId} to ${input.projectId}$parentNote. " +
                "contentHash=$finalHash. Edit via update_source_node_body.",
            data = Output(
                projectId = input.projectId,
                nodeId = input.nodeId,
                kind = input.kind,
                contentHash = finalHash,
                parentIds = input.parentIds,
            ),
        )
    }
}
