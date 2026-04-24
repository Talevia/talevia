package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.AutoRegenHint
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.removeNode
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Three-way source-node verb — the consolidated action-dispatched form that
 * replaces the previous `AddSourceNodeTool` + `RemoveSourceNodeTool` +
 * `ForkSourceNodeTool` trio (`debt-source-consolidate-add-remove-fork`,
 * 2026-04-24, following `ClipActionTool` / `TransitionActionTool` /
 * `SessionActionTool` precedent).
 *
 * Each verb mutates the project's [Source] DAG under
 * [ProjectStore.mutateSource]. Folding them into one tool cuts two top-level
 * LLM tool-spec entries (≈ 400 tokens per turn saved) without losing any
 * behavioural surface. `RenameSourceNodeTool` and the body / parents / import
 * / export tools stay separate — they carry distinct shapes and invariants
 * that don't collapse cleanly into an action-tagged Input.
 *
 * Action-specific payload fields are nullable-per-action, same pattern the
 * earlier action tools use — kotlinx.serialization sealed-class variants
 * would blow up the JSON Schema surface that the LLM reads without buying
 * anything the per-action validation in `execute()` doesn't already
 * provide.
 *
 * ## Actions
 *
 * - `action="add"` + `nodeId` + `kind` + optional `body` + optional
 *   `parentIds` — create a source node of any kind with an opaque JSON
 *   body. Rejects blank kind, blank nodeId, duplicate ids, and parent
 *   ids that don't exist in the project's source graph.
 * - `action="remove"` + `nodeId` — delete one source node. Does **not**
 *   cascade to descendant nodes or bound clips — clips that referenced
 *   the removed node via `sourceBinding` will show as always-stale on
 *   the next staleness check (matches legacy `remove_source_node`).
 *   Emits an `autoRegenHint` when the mutation leaves any clip stale.
 * - `action="fork"` + `sourceNodeId` + optional `newNodeId` — duplicate
 *   a source node under a fresh id within the same project. Parents are
 *   *referenced*, not cloned (shares the same character_ref /
 *   style_bible ancestors as the original). Body copied verbatim so the
 *   forked node's contentHash matches the source's — AIGC lockfile cache
 *   hits transfer automatically until the caller tweaks the fork via
 *   `update_source_node_body`.
 */
@OptIn(ExperimentalUuidApi::class)
class SourceNodeActionTool(
    private val projects: ProjectStore,
) : Tool<SourceNodeActionTool.Input, SourceNodeActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** `"add"`, `"remove"`, or `"fork"`. Case-sensitive. */
        val action: String,
        /**
         * `action="add"`: required — the id of the new node.
         * `action="remove"`: required — the id of the node to delete.
         * `action="fork"`: ignored — use `sourceNodeId` for the original
         * and `newNodeId` for the fork's id.
         */
        val nodeId: String? = null,
        /** `action="add"` only. Dotted-namespace kind string. */
        val kind: String? = null,
        /** `action="add"` only. Opaque body matching the genre's shape. */
        val body: JsonObject? = null,
        /** `action="add"` only. Optional parent node ids; each must exist. */
        val parentIds: List<String>? = null,
        /** `action="fork"` only. Source node to duplicate. */
        val sourceNodeId: String? = null,
        /** `action="fork"` only. Optional new id; UUID minted when blank. */
        val newNodeId: String? = null,
    )

    @Serializable data class AddResult(
        val nodeId: String,
        val kind: String,
        val contentHash: String,
        val parentIds: List<String>,
    )

    @Serializable data class RemoveResult(
        val nodeId: String,
        val removedKind: String,
    )

    @Serializable data class ForkResult(
        val sourceNodeId: String,
        val forkedNodeId: String,
        val kind: String,
        val contentHash: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        /** Populated when `action="add"`. */
        val added: List<AddResult> = emptyList(),
        /** Populated when `action="remove"`. */
        val removed: List<RemoveResult> = emptyList(),
        /** Populated when `action="fork"`. */
        val forked: List<ForkResult> = emptyList(),
        /**
         * VISION §5.5 auto-regen hint: non-null when any clip in the
         * project is now stale after the mutation. Populated by
         * `action="remove"` today — add / fork create new nodes that
         * have no lockfile-bound clips yet, so they can't stale anything.
         */
        val autoRegenHint: AutoRegenHint? = null,
    )

    override val id: String = "source_node_action"
    override val helpText: String =
        "Three-way source-DAG verb dispatching on `action`. " +
            "`action=\"add\"` + `nodeId` + `kind` (dotted-namespace string, e.g. narrative.scene, " +
            "core.consistency.character_ref) + optional `body` (opaque JSON, {} default) + optional " +
            "`parentIds` — create a node; rejects blank kind, blank id, duplicate ids, dangling parents. " +
            "`action=\"remove\"` + `nodeId` — delete one node; does not cascade to descendant nodes or " +
            "clips (bound clips will surface as stale on the next check). " +
            "`action=\"fork\"` + `sourceNodeId` + optional `newNodeId` — duplicate a node under a fresh " +
            "id within the same project; parents referenced not cloned, body copied verbatim so " +
            "contentHash matches the source (AIGC cache hits transfer until the fork is tweaked). " +
            "Use update_source_node_body to edit bodies, set_source_node_parents to re-parent, " +
            "rename_source_node to rewrite ids atomically."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`add` to create a node, `remove` to delete one, `fork` to duplicate under a new id.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("add"),
                            JsonPrimitive("remove"),
                            JsonPrimitive("fork"),
                        ),
                    ),
                )
            }
            putJsonObject("nodeId") {
                put("type", "string")
                put(
                    "description",
                    "Required when action=add (new node's id) or action=remove (node to delete).",
                )
            }
            putJsonObject("kind") {
                put("type", "string")
                put(
                    "description",
                    "action=add only. Dotted-namespace kind string (e.g. narrative.scene, " +
                        "musicmv.track, ad.variant_request). Must match what the genre layer " +
                        "expects — Core does not validate.",
                )
            }
            putJsonObject("body") {
                put("type", "object")
                put(
                    "description",
                    "action=add only. Opaque JSON body matching the genre's shape. Defaults to {}. " +
                        "Kind + body together drive the contentHash and thus downstream staleness.",
                )
            }
            putJsonObject("parentIds") {
                put("type", "array")
                put(
                    "description",
                    "action=add only. Optional parent node ids the new node depends on. Each must " +
                        "already exist in the project's source graph. Empty (default) means root node.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("sourceNodeId") {
                put("type", "string")
                put("description", "action=fork only. Id of the node to duplicate.")
            }
            putJsonObject("newNodeId") {
                put("type", "string")
                put(
                    "description",
                    "action=fork only. Optional new id for the forked node. Auto-generated UUID if " +
                        "blank. Collides with an existing node id -> loud error.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("action"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        return when (input.action) {
            "add" -> executeAdd(input)
            "remove" -> executeRemove(input)
            "fork" -> executeFork(input)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, fork",
            )
        }
    }

    private suspend fun executeAdd(input: Input): ToolResult<Output> {
        val nodeIdRaw = input.nodeId
            ?: error("action=add requires `nodeId` (omit `sourceNodeId` / `newNodeId`)")
        val kind = input.kind
            ?: error("action=add requires `kind`")
        require(input.sourceNodeId == null && input.newNodeId == null) {
            "action=add rejects `sourceNodeId` / `newNodeId` — those are action=fork fields"
        }
        require(kind.isNotBlank()) { "kind must not be blank" }
        require(nodeIdRaw.isNotBlank()) { "nodeId must not be blank" }

        val body = input.body ?: JsonObject(emptyMap())
        val parentIds = input.parentIds ?: emptyList()

        val pid = ProjectId(input.projectId)
        val newId = SourceNodeId(nodeIdRaw)
        val parentRefs = parentIds.map { SourceRef(SourceNodeId(it)) }

        var finalHash = ""
        projects.mutateSource(pid) { source ->
            require(newId !in source.byId) {
                "Source node $nodeIdRaw already exists in project ${input.projectId}. " +
                    "Use update_source_node_body to edit its body, or pick a fresh id."
            }
            val missing = parentRefs.map { it.nodeId }.filter { it !in source.byId }
            require(missing.isEmpty()) {
                "Parent node(s) not found in project ${input.projectId}: " +
                    "${missing.joinToString(", ") { it.value }}. Create them first or pass an empty parentIds."
            }
            val node = SourceNode.create(
                id = newId,
                kind = kind,
                body = body,
                parents = parentRefs,
            )
            val next = source.addNode(node)
            finalHash = next.byId[newId]!!.contentHash
            next
        }

        val parentNote = if (parentRefs.isEmpty()) "" else " parents=[${parentIds.joinToString(",")}]"
        return ToolResult(
            title = "add source $kind $nodeIdRaw",
            outputForLlm = "Added $kind node $nodeIdRaw to ${input.projectId}$parentNote. " +
                "contentHash=$finalHash. Edit via update_source_node_body.",
            data = Output(
                projectId = input.projectId,
                action = "add",
                added = listOf(
                    AddResult(
                        nodeId = nodeIdRaw,
                        kind = kind,
                        contentHash = finalHash,
                        parentIds = parentIds,
                    ),
                ),
            ),
        )
    }

    private suspend fun executeRemove(input: Input): ToolResult<Output> {
        val nodeIdRaw = input.nodeId
            ?: error("action=remove requires `nodeId`")
        require(
            input.kind == null &&
                input.body == null &&
                input.parentIds == null &&
                input.sourceNodeId == null &&
                input.newNodeId == null,
        ) {
            "action=remove rejects add/fork payload fields — only `nodeId` is accepted"
        }

        val pid = ProjectId(input.projectId)
        val nodeId = SourceNodeId(nodeIdRaw)
        var removedKind = ""
        val updated = projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error("Source node $nodeIdRaw not found in project ${input.projectId}")
            removedKind = existing.kind
            source.removeNode(nodeId)
        }
        val hint = updated.autoRegenHint()
        val regenNudge = if (hint != null) {
            " autoRegenHint: ${hint.staleClipCount} stale clip(s) — suggested next: ${hint.suggestedTool}."
        } else {
            ""
        }
        return ToolResult(
            title = "remove source node $nodeIdRaw",
            outputForLlm = "Removed $removedKind node $nodeIdRaw. " +
                "Clips that bound this id will be re-rendered next export.$regenNudge",
            data = Output(
                projectId = input.projectId,
                action = "remove",
                removed = listOf(RemoveResult(nodeId = nodeIdRaw, removedKind = removedKind)),
                autoRegenHint = hint,
            ),
        )
    }

    private suspend fun executeFork(input: Input): ToolResult<Output> {
        val sourceNodeIdRaw = input.sourceNodeId
            ?: error("action=fork requires `sourceNodeId`")
        require(input.nodeId == null && input.kind == null && input.body == null && input.parentIds == null) {
            "action=fork rejects add/remove payload fields — use `sourceNodeId` + optional `newNodeId`"
        }

        val pid = ProjectId(input.projectId)
        projects.get(pid) ?: error("Project ${input.projectId} not found")

        val requestedNewId = input.newNodeId?.trim().takeIf { !it.isNullOrBlank() }
        require(requestedNewId != sourceNodeIdRaw) {
            "newNodeId ($sourceNodeIdRaw) equals sourceNodeId — fork must produce a distinct id"
        }
        val forkedId = SourceNodeId(requestedNewId ?: Uuid.random().toString())

        var kindOut: String? = null
        var hashOut: String? = null

        projects.mutateSource(pid) { source ->
            val original = source.nodes.firstOrNull { it.id.value == sourceNodeIdRaw }
                ?: error("Source node $sourceNodeIdRaw not found in project ${input.projectId}")
            if (source.nodes.any { it.id == forkedId }) {
                error(
                    "Source node ${forkedId.value} already exists in project ${input.projectId}. " +
                        "Pick a different newNodeId (or source_node_action(action=remove) first).",
                )
            }
            kindOut = original.kind
            val forked = SourceNode.create(
                id = forkedId,
                kind = original.kind,
                body = original.body,
                parents = original.parents,
            )
            hashOut = forked.contentHash
            source.addNode(forked)
        }

        return ToolResult(
            title = "fork ${kindOut} ${forkedId.value}",
            outputForLlm = "Forked source node $sourceNodeIdRaw -> ${forkedId.value} " +
                "(kind=$kindOut, contentHash=$hashOut). Parents preserved; body copied verbatim. " +
                "Tweak with update_source_node_body to diverge from the original.",
            data = Output(
                projectId = input.projectId,
                action = "fork",
                forked = listOf(
                    ForkResult(
                        sourceNodeId = sourceNodeIdRaw,
                        forkedNodeId = forkedId.value,
                        kind = kindOut!!,
                        contentHash = hashOut!!,
                    ),
                ),
            ),
        )
    }
}
