package io.talevia.core.tool.builtin.source

import io.talevia.core.domain.AutoRegenHint
import io.talevia.core.domain.ProjectStore
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
 * Four-way source-node verb — the consolidated action-dispatched form that
 * replaces the previous `AddSourceNodeTool` + `RemoveSourceNodeTool` +
 * `ForkSourceNodeTool` trio (`debt-source-consolidate-add-remove-fork`,
 * 2026-04-24, following `ClipActionTool` / `TransitionActionTool` /
 * `SessionActionTool` precedent), extended with `action="rename"` in
 * `debt-source-rename-evaluate` (2026-04-23) once the structural rewrite
 * mechanics could be lifted into the `domain.source` package.
 *
 * Each verb mutates the project's [Source] DAG under
 * [ProjectStore.mutateSource] (add / remove / fork) or
 * [ProjectStore.mutate] (rename — touches timeline + lockfile as well).
 * Folding them into one tool cuts top-level LLM tool-spec entries
 * (~400 tokens per turn per fold) without losing any behavioural surface.
 * Body / parents / import / export tools stay separate — they carry
 * distinct shapes and invariants that don't collapse cleanly into an
 * action-tagged Input.
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
 * - `action="rename"` + `oldId` + `newId` — atomically rewrite a node id
 *   at every surface that stores it: the node itself, every descendant's
 *   `parents` ref, every [Clip.sourceBinding], and every lockfile entry's
 *   `sourceBinding` / `sourceContentHashes` key. Runs in a single
 *   `mutate` block so partial-state windows don't exist. Emits one
 *   [Part.TimelineSnapshot] when at least one clip binding was rewritten
 *   so `revert_timeline` can unwind. Does **not** touch string ids
 *   embedded inside typed bodies (e.g. `narrative.shot.body.sceneId`) —
 *   that's the genre layer's job via the kind-specific `update_*` tool.
 */
class SourceNodeActionTool(
    private val projects: ProjectStore,
) : Tool<SourceNodeActionTool.Input, SourceNodeActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** `"add"`, `"remove"`, `"fork"`, or `"rename"`. Case-sensitive. */
        val action: String,
        /**
         * `action="add"`: required — the id of the new node.
         * `action="remove"`: required — the id of the node to delete.
         * `action="fork"`: ignored — use `sourceNodeId` for the original
         * and `newNodeId` for the fork's id.
         * `action="rename"`: ignored — use `oldId` / `newId`.
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
        /** `action="rename"` only. Existing id to rewrite. */
        val oldId: String? = null,
        /**
         * `action="rename"` only. New id. Must match the slug shape
         * (lowercase letters / digits / `-`), must not collide with an
         * existing node, and same as `oldId` is a no-op.
         */
        val newId: String? = null,
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

    @Serializable data class RenameResult(
        val oldId: String,
        val newId: String,
        /** Nodes whose [SourceNode.parents] list was rewritten. */
        val parentsRewrittenCount: Int,
        /** Clips whose [Clip.sourceBinding] set was rewritten. */
        val clipsRewrittenCount: Int,
        /** Lockfile entries whose `sourceBinding` / `sourceContentHashes` were rewritten. */
        val lockfileEntriesRewrittenCount: Int,
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
        /** Populated when `action="rename"`. */
        val renamed: List<RenameResult> = emptyList(),
        /**
         * VISION §5.5 auto-regen hint: non-null when any clip in the
         * project is now stale after the mutation. Populated by
         * `action="remove"` today — add / fork create new nodes that
         * have no lockfile-bound clips yet, so they can't stale anything;
         * rename rewires bindings rather than dropping them, so it also
         * leaves staleness unchanged (descendant `contentHash` cascades
         * are the staleness signal there, surfaced by `find_stale_clips`).
         */
        val autoRegenHint: AutoRegenHint? = null,
    )

    override val id: String = "source_node_action"
    override val helpText: String =
        "Four-way source-DAG verb dispatching on `action`. " +
            "`action=\"add\"` + `nodeId` + `kind` (dotted-namespace string, e.g. narrative.scene, " +
            "core.consistency.character_ref) + optional `body` (opaque JSON, {} default) + optional " +
            "`parentIds` — create a node; rejects blank kind, blank id, duplicate ids, dangling parents. " +
            "`action=\"remove\"` + `nodeId` — delete one node; does not cascade to descendant nodes or " +
            "clips (bound clips will surface as stale on the next check). " +
            "`action=\"fork\"` + `sourceNodeId` + optional `newNodeId` — duplicate a node under a fresh " +
            "id within the same project; parents referenced not cloned, body copied verbatim so " +
            "contentHash matches the source (AIGC cache hits transfer until the fork is tweaked). " +
            "`action=\"rename\"` + `oldId` + `newId` — atomically rewrite the node itself, every " +
            "descendant's parent-ref, every clip's sourceBinding, and every lockfile entry's binding + " +
            "sourceContentHashes keys in one mutation; does NOT rewrite string ids embedded inside " +
            "typed bodies (update those separately via the kind-specific update_* tool); newId must " +
            "match the source-id slug shape (lowercase letters / digits / '-'); same-id is a no-op; " +
            "rejects loudly on unknown oldId or newId collision. " +
            "Use update_source_node_body to edit bodies, set_source_node_parents to re-parent."
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
                    "`add` to create a node, `remove` to delete one, `fork` to duplicate under a new " +
                        "id, `rename` to atomically rewrite an id everywhere it's referenced.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("add"),
                            JsonPrimitive("remove"),
                            JsonPrimitive("fork"),
                            JsonPrimitive("rename"),
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
            putJsonObject("oldId") {
                put("type", "string")
                put("description", "action=rename only. Existing source-node id to rewrite.")
            }
            putJsonObject("newId") {
                put("type", "string")
                put(
                    "description",
                    "action=rename only. New id. Must be lowercase letters / digits / '-', non-empty, " +
                        "and must not collide with an existing node.",
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
            "add" -> executeSourceAdd(projects, input)
            "remove" -> executeSourceRemove(projects, input)
            "fork" -> executeSourceFork(projects, input)
            "rename" -> executeSourceRename(projects, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, fork, rename",
            )
        }
    }
}
