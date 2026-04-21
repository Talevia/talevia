package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
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
 * Replace a source node's [io.talevia.core.domain.source.SourceNode.body] wholesale —
 * the generic, kind-agnostic body editor the update_* trio deliberately does not cover.
 *
 * The three `set_character_ref` / `set_style_bible` / `set_brand_palette` tools
 * exist for the consistency kinds because those have typed bodies, field-level
 * semantics (which field to clear vs patch), and a small enough surface that a
 * partial-patch ergonomic is worth the extra code. Every other kind — narrative.shot,
 * vlog.raw_footage, musicmv.*, tutorial.*, ad.*, or anything the agent created via
 * `import_source_node` — has no body-editing path. The workaround was
 * `remove_source_node` + re-`import_source_node` which:
 *
 *   1. Loses the id (every clip `sourceBinding`, every `parents` ref, every
 *      lockfile `sourceBinding` entry is dropped),
 *   2. Forces the agent to re-specify the whole body plus re-bind every descendant,
 *   3. Is one of the most error-prone multi-step flows we ask the model to execute.
 *
 * This tool closes that gap with full-replacement semantics. Partial-patch is
 * deliberately out of scope — the caller is expected to round-trip via
 * `describe_source_node` (read current body), mutate client-side, and write back.
 * A generic JSON merge has too many ambiguities (null = clear or keep? arrays =
 * replace or concat?) for a generic tool to make one choice the agent will
 * always read as intended; whole-body replace is unambiguous.
 *
 * **Scope — body only.**
 *  - `kind` is not editable here. Changing a node's kind is a type change, not an
 *    edit; it would invalidate every reader that dispatches on kind. If the agent
 *    really wants a different kind, it should remove + import fresh.
 *  - `parents` is not editable here. Use `set_source_node_parents` — keeping the
 *    two verbs orthogonal matches the existing update_* contracts (which accept
 *    `parentIds` only because they already take structured body input).
 *  - `id` is not editable here. Use `rename_source_node` for atomic id refactors.
 *
 * **contentHash cascade.** `SourceNode.contentHash` is `(kind, body, parents)`, so
 * the edited node's hash changes. Descendants whose `parents` list contained this
 * node do **not** re-hash on a body edit (the parent-ref value — just the nodeId —
 * is unchanged); they become stale via the standard DAG-walk in
 * `find_stale_clips`, which compares a clip's binding hash snapshot against the
 * current node hashes including ancestors. That's the same lane every other
 * update-style tool uses; no new machinery.
 *
 * **No TimelineSnapshot.** Body edits touch zero [io.talevia.core.domain.Clip]
 * fields (`sourceBinding` is by id, not by hash), so `revert_timeline` would be a
 * no-op anyway. Following the pattern of `set_character_ref` /
 * `set_source_node_parents`, which also don't emit snapshots for pure-source
 * mutations. Project-level undo for source edits lives in
 * `save_project_snapshot` / `restore_project_snapshot`.
 *
 * **Permission.** `source.write` — same tier as `set_character_ref` /
 * `set_source_node_parents`.
 */
class UpdateSourceNodeBodyTool(
    private val projects: ProjectStore,
) : Tool<UpdateSourceNodeBodyTool.Input, UpdateSourceNodeBodyTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        /** Full replacement. Every field the caller wants to keep must appear in this object. */
        val body: JsonObject,
    )

    @Serializable data class Output(
        val projectId: String,
        val nodeId: String,
        val kind: String,
        val previousContentHash: String,
        val newContentHash: String,
        /**
         * Count of clips whose `sourceBinding` includes this nodeId directly — the
         * immediate blast-radius hint. A non-zero value means `find_stale_clips` will
         * return these clips as stale on the next check.
         */
        val boundClipCount: Int,
    )

    override val id: String = "update_source_node_body"
    override val helpText: String =
        "Replace a source node's body wholesale (full replacement — no partial-patch). Kind-agnostic: " +
            "works on any node kind (narrative.shot, vlog.raw_footage, musicmv.*, tutorial.*, ad.*, " +
            "or any imported/hand-authored node). Use set_character_ref / set_style_bible / " +
            "set_brand_palette instead for those three consistency kinds when you want partial-patch " +
            "ergonomics. Does NOT touch kind (rebuild the node if the kind must change), parents " +
            "(use set_source_node_parents), or id (use rename_source_node). Bumps contentHash so " +
            "bound clips go stale — run find_stale_clips after editing to surface them."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Id of the node whose body is being replaced.")
            }
            putJsonObject("body") {
                put("type", "object")
                put(
                    "description",
                    "Full replacement body. Kind is preserved; parents are preserved; contentHash " +
                        "is recomputed.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("nodeId"),
                    JsonPrimitive("body"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val nodeId = SourceNodeId(input.nodeId)

        var previousHash = ""
        var newHash = ""
        var kindSeen = ""
        var boundClipCount = 0

        val updated = projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error(
                    "Source node ${nodeId.value} not found in project ${input.projectId}. " +
                        "Call source_query(select=nodes) or describe_source_node to discover available ids.",
                )
            previousHash = existing.contentHash
            kindSeen = existing.kind
            source.replaceNode(nodeId) { node -> node.copy(body = input.body) }
        }

        newHash = updated.source.byId[nodeId]!!.contentHash
        boundClipCount = updated.timeline.tracks.sumOf { track ->
            track.clips.count { nodeId in it.sourceBinding }
        }

        val staleHint = if (boundClipCount == 0) {
            " No clips bind this node directly; DAG descendants unchanged (body edits don't rewrite " +
                "parent refs). Call find_stale_clips if you want to surface transitively-stale clips."
        } else {
            " $boundClipCount clip(s) bind this node directly — they will show up as stale in " +
                "find_stale_clips until re-rendered."
        }

        return ToolResult(
            title = "update source body for ${input.nodeId}",
            outputForLlm = "Replaced body of ${input.nodeId} (${kindSeen}). " +
                "contentHash $previousHash → $newHash.$staleHint",
            data = Output(
                projectId = input.projectId,
                nodeId = input.nodeId,
                kind = kindSeen,
                previousContentHash = previousHash,
                newContentHash = newHash,
                boundClipCount = boundClipCount,
            ),
        )
    }
}
