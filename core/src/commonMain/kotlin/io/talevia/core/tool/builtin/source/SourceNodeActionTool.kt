package io.talevia.core.tool.builtin.source

import io.talevia.core.domain.AutoRegenHint
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Six-way source-node verb — the consolidated action-dispatched form that
 * replaces the previous `AddSourceNodeTool` + `RemoveSourceNodeTool` +
 * `ForkSourceNodeTool` trio (`debt-source-consolidate-add-remove-fork`,
 * 2026-04-24, following `ClipActionTool` / `TransitionActionTool` /
 * `SessionActionTool` precedent), extended with `action="rename"` in
 * `debt-source-rename-evaluate` (2026-04-23), and further extended with
 * `action="update_body"` + `action="set_parents"` in
 * `debt-source-singleton-tools-fold` (2026-04-25) to absorb the last
 * two free-standing source-edit tools.
 *
 * Each verb mutates the project's [io.talevia.core.domain.source.Source]
 * DAG under [ProjectStore.mutateSource] (add / remove / fork /
 * update_body / set_parents) or [ProjectStore.mutate] (rename — touches
 * timeline + lockfile as well). Folding them into one tool cuts
 * top-level LLM tool-spec entries (~400 tokens per turn per fold)
 * without losing any behavioural surface. Import / export / describe /
 * diff stay separate — they carry distinct shapes (envelope-bundling,
 * audit-only outputs) that don't collapse cleanly into an
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
 *   `parents` ref, every [io.talevia.core.domain.Clip.sourceBinding],
 *   and every lockfile entry's `sourceBinding` /
 *   `sourceContentHashes` key. Runs in a single `mutate` block so
 *   partial-state windows don't exist. Emits one
 *   [io.talevia.core.session.Part.TimelineSnapshot] when at least one
 *   clip binding was rewritten so `revert_timeline` can unwind. Does
 *   **not** touch string ids embedded inside typed bodies (e.g.
 *   `narrative.shot.body.sceneId`) — that's the genre layer's job via
 *   the kind-specific `update_*` tool.
 * - `action="update_body"` + `nodeId` + (one of: `body` /
 *   `restoreFromRevisionIndex` / `mergeFromRevisionIndex` +
 *   `mergeFieldPaths`) — replace a source node's `body` wholesale.
 *   Kind-agnostic; does not touch kind / parents / id (use the
 *   matching action verbs). Bumps `contentHash` so bound clips go
 *   stale; `project_query(select=stale_clips)` surfaces them. Three input modes
 *   (mutually exclusive): full-replacement object, restore-by-history-
 *   index, or per-field merge from a historical revision. Empty body
 *   is rejected. The dispatcher's [InputCompatSerializer] rescues the
 *   "flattened body" shape some LLMs emit (top-level body fields
 *   alongside `projectId`/`nodeId`/`action`) — only kicks in when
 *   `action="update_body"` and the typed `body` slot is missing.
 * - `action="set_parents"` + `nodeId` + `parentIds` — replace a
 *   node's parent list wholesale. Empty list clears all parents.
 *   Cycles + dangling ids are rejected loudly. Bumps `contentHash`
 *   for the same staleness lane.
 */
class SourceNodeActionTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<SourceNodeActionTool.Input, SourceNodeActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * `"add"`, `"remove"`, `"fork"`, `"rename"`, `"update_body"`,
         * `"set_parents"`, or `"import"`. Case-sensitive.
         */
        val action: String,
        /**
         * `action="add"`: required — the id of the new node.
         * `action="remove"`: required — the id of the node to delete.
         * `action="update_body"`: required — the node whose body is
         *   being replaced.
         * `action="set_parents"`: required — the node whose parents are
         *   being rewritten.
         * `action="fork"`: ignored — use `sourceNodeId` for the original
         *   and `newNodeId` for the fork's id.
         * `action="rename"`: ignored — use `oldId` / `newId`.
         */
        val nodeId: String? = null,
        /** `action="add"` only. Dotted-namespace kind string. */
        val kind: String? = null,
        /**
         * `action="add"`: opaque body matching the genre's shape (defaults
         * to `{}`). `action="update_body"`: full-replacement body —
         * required unless one of the historical-revision modes is set.
         */
        val body: JsonObject? = null,
        /**
         * `action="add"`: optional parent node ids; each must exist.
         * `action="set_parents"`: required — full replacement parent
         * list. Empty list clears all parents.
         */
        val parentIds: List<String>? = null,
        /** `action="fork"` only. Source node to duplicate. */
        val sourceNodeId: String? = null,
        /**
         * `action="fork"` or `action="import"`. Optional new id; for
         * fork, UUID minted when blank; for import, leaf node retains
         * its source id when blank, gets renamed to this value when
         * non-blank. Collides with an existing node id → loud error.
         */
        val newNodeId: String? = null,
        /** `action="rename"` only. Existing id to rewrite. */
        val oldId: String? = null,
        /**
         * `action="rename"` only. New id. Must match the slug shape
         * (lowercase letters / digits / `-`), must not collide with an
         * existing node, and same as `oldId` is a no-op.
         */
        val newId: String? = null,
        /**
         * `action="update_body"` only. Restore an earlier body from this
         * node's revision history — 0 = most-recent overwritten revision.
         * Mutually exclusive with `body` and `mergeFromRevisionIndex`.
         */
        val restoreFromRevisionIndex: Int? = null,
        /**
         * `action="update_body"` only. Per-field merge from a historical
         * revision. [mergeFieldPaths] names the top-level keys copied
         * from that revision over the current body. Mutually exclusive
         * with `body` and `restoreFromRevisionIndex`.
         */
        val mergeFromRevisionIndex: Int? = null,
        /**
         * `action="update_body"` only. Top-level body keys to take from
         * `mergeFromRevisionIndex`'s historical body. Required when
         * `mergeFromRevisionIndex` is set; rejected otherwise. Every
         * named key must exist in the historical revision.
         */
        val mergeFieldPaths: List<String>? = null,
        /**
         * `action="import"` only. Source project id for live cross-
         * project copy. Pair with `fromNodeId`; mutually exclusive
         * with `envelope`.
         */
        val fromProjectId: String? = null,
        /**
         * `action="import"` only. Source node id for live cross-
         * project copy. Pair with `fromProjectId`.
         */
        val fromNodeId: String? = null,
        /**
         * `action="import"` only. Portable JSON envelope produced by
         * `export_source_node`. Mutually exclusive with the
         * `fromProjectId + fromNodeId` pair.
         */
        val envelope: String? = null,
    )

    /**
     * Tolerant deserializer that rescues the "flattened body" shape some
     * LLMs emit on `action="update_body"` — instead of nesting body
     * fields under `body`, they splat them at the top level alongside
     * `projectId` / `nodeId` / `action`:
     *
     *   BAD  {"projectId":"p","action":"update_body","nodeId":"shot-1","framing":"wide"}
     *   GOOD {"projectId":"p","action":"update_body","nodeId":"shot-1","body":{"framing":"wide"}}
     *
     * Inherited from the legacy `UpdateSourceNodeBodyTool.InputCompatSerializer`
     * (gpt-5.4-mini repeatedly produced the BAD shape even with explicit
     * helpText guidance). Only fires for `action="update_body"` and only
     * when no proper `body` object is present — every other action
     * passes through untransformed so add / remove / fork / rename /
     * set_parents callers don't pay the rescue cost.
     */
    internal object InputCompatSerializer :
        JsonTransformingSerializer<Input>(serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement {
            val obj = element as? JsonObject ?: return element
            val action = (obj["action"] as? JsonPrimitive)?.contentOrNull() ?: return element
            if (action != "update_body") return element
            if (obj["body"] is JsonObject) return element
            val flatKeys = obj.keys - RESERVED_UPDATE_BODY_KEYS
            if (flatKeys.isEmpty()) return element
            val rescuedBody = buildJsonObject {
                obj.forEach { (k, v) ->
                    if (k !in RESERVED_UPDATE_BODY_KEYS) put(k, v)
                }
            }
            return buildJsonObject {
                obj.forEach { (k, v) ->
                    if (k in RESERVED_UPDATE_BODY_KEYS) put(k, v)
                }
                put("body", rescuedBody)
            }
        }

        private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
    }

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
        /** Nodes whose [io.talevia.core.domain.source.SourceNode.parents] list was rewritten. */
        val parentsRewrittenCount: Int,
        /** Clips whose [io.talevia.core.domain.Clip.sourceBinding] set was rewritten. */
        val clipsRewrittenCount: Int,
        /** Lockfile entries whose `sourceBinding` / `sourceContentHashes` were rewritten. */
        val lockfileEntriesRewrittenCount: Int,
    )

    @Serializable data class UpdateBodyResult(
        val nodeId: String,
        val kind: String,
        val previousContentHash: String,
        val newContentHash: String,
        /**
         * Count of clips whose `sourceBinding` includes this nodeId
         * directly — the immediate blast-radius hint. A non-zero value
         * means `project_query(select=stale_clips)` returns these clips on the next
         * check.
         */
        val boundClipCount: Int,
    )

    @Serializable data class SetParentsResult(
        val nodeId: String,
        val previousParentIds: List<String>,
        val newParentIds: List<String>,
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
        /** Populated when `action="update_body"`. */
        val updatedBody: List<UpdateBodyResult> = emptyList(),
        /** Populated when `action="set_parents"`. */
        val parentsSet: List<SetParentsResult> = emptyList(),
        /**
         * Populated when `action="import"`. One row per node that
         * landed (or skipped because the target had a content-hash-
         * equivalent node already).
         */
        val imported: List<SourceNodeImportedNode> = emptyList(),
        /**
         * `action="import"` only. Source project id when the call
         * used the live `(fromProjectId, fromNodeId)` shape; null on
         * the envelope path (envelope carries no source project id).
         */
        val importFromProjectId: String? = null,
        /**
         * `action="import"` only. Echoed envelope `formatVersion`
         * after a successful round-trip on the envelope path; null
         * on the live cross-project path.
         */
        val importedFormatVersion: String? = null,
        /**
         * VISION §5.5 auto-regen hint: non-null when any clip in the
         * project is now stale after the mutation. Populated by
         * `action="remove"` / `action="update_body"` /
         * `action="set_parents"` today — add / fork create new nodes
         * that have no lockfile-bound clips yet, so they can't stale
         * anything; rename rewires bindings rather than dropping them,
         * so it also leaves staleness unchanged (descendant
         * `contentHash` cascades are the staleness signal there,
         * surfaced by `project_query(select=stale_clips)`).
         */
        val autoRegenHint: AutoRegenHint? = null,
    )

    override val id: String = "source_node_action"
    override val helpText: String =
        "7-verb source-DAG dispatcher: add / remove / fork / rename / update_body / set_parents / " +
            "import. Per-verb fields in schema. Cross-cutting: " +
            "add rejects blank kind/id, duplicate ids, dangling parents. " +
            "remove does NOT cascade (descendants + bound clips surface as stale). " +
            "fork copies body verbatim (contentHash matches → AIGC cache transfers until tweaked). " +
            "rename rewrites node + every descendant parent-ref + every clip sourceBinding + every " +
            "lockfile sourceContentHashes key atomically; does NOT rewrite ids inside typed bodies; " +
            "newId must be lowercase-letters-digits-hyphen; same-id no-op; collision fails. " +
            "update_body: one of body (full replace) / restoreFromRevisionIndex=N / " +
            "mergeFromRevisionIndex=N + mergeFieldPaths; kind-agnostic, doesn't touch kind/parents/id; " +
            "bumps contentHash → bound clips go stale; empty body rejected. " +
            "set_parents: full replacement (empty clears); cycles + dangling rejected; bumps contentHash."
    override val inputSerializer: KSerializer<Input> = InputCompatSerializer
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    /**
     * Schema lives in [SourceNodeActionToolSchema] (extracted cycle 136
     * to mirror `ClipActionToolSchema.kt` / `ProjectActionToolSchema.kt`
     * — keeps this file under the long-file threshold while letting the
     * schema grow with future verbs without re-puffing the dispatcher).
     */
    override val inputSchema: JsonObject = SOURCE_NODE_ACTION_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        return when (input.action) {
            "add" -> executeSourceAdd(projects, input)
            "remove" -> executeSourceRemove(projects, input)
            "fork" -> executeSourceFork(projects, input)
            "rename" -> executeSourceRename(projects, input, ctx)
            "import" -> executeSourceImport(projects, input, ctx)
            "update_body" -> executeSourceUpdateBody(projects, input, clock)
            "set_parents" -> executeSourceSetParents(projects, input)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, fork, rename, " +
                    "update_body, set_parents",
            )
        }
    }

    internal companion object {
        /**
         * Top-level keys preserved as-is during the
         * [InputCompatSerializer] flatten-body rescue. Anything outside
         * this set on `action="update_body"` gets folded into a synthesized
         * `body` object before kotlinx.serialization decodes the typed
         * fields.
         */
        internal val RESERVED_UPDATE_BODY_KEYS: Set<String> = setOf(
            "projectId",
            "action",
            "nodeId",
            "body",
            "restoreFromRevisionIndex",
            "mergeFromRevisionIndex",
            "mergeFieldPaths",
        )
    }
}
