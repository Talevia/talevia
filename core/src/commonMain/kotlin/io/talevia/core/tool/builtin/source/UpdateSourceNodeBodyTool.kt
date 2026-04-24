package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.AutoRegenHint
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.BodyRevision
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Replace a source node's [io.talevia.core.domain.source.SourceNode.body] wholesale —
 * the single body editor for every kind, consistency or otherwise.
 *
 * The consistency kinds (character_ref / style_bible / brand_palette) once had
 * bespoke `set_*` tools with partial-patch semantics; they were removed in
 * favour of the kind-agnostic pair `source_node_action(action="add")` +
 * `update_source_node_body`
 * (see docs/decisions/2026-04-22-debt-fold-set-source-node-body-helpers.md).
 * Partial-patch is deliberately out of scope — the caller is expected to
 * round-trip via `describe_source_node` (read current body), mutate
 * client-side, and write back. A generic JSON merge has too many ambiguities
 * (null = clear or keep? arrays = replace or concat?) for a generic tool to
 * make one choice the agent will always read as intended; whole-body replace
 * is unambiguous.
 *
 * **Scope — body only.**
 *  - `kind` is not editable here. Changing a node's kind is a type change, not an
 *    edit; it would invalidate every reader that dispatches on kind. If the agent
 *    really wants a different kind, it should remove + import fresh.
 *  - `parents` is not editable here. Use `set_source_node_parents` — keeping the
 *    two verbs orthogonal matches the existing update_* contracts (which accept
 *    `parentIds` only because they already take structured body input).
 *  - `id` is not editable here. Use `source_node_action(action="rename")` for atomic id refactors.
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
 * no-op anyway. Following the same pattern as `set_source_node_parents`, which
 * also doesn't emit snapshots for pure-source mutations. Project-level undo
 * for source edits lives in `project_snapshot_action(action=save)` / `project_snapshot_action(action=restore)`.
 *
 * **Permission.** `source.write` — same tier as the rest of the source-write family.
 */
class UpdateSourceNodeBodyTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<UpdateSourceNodeBodyTool.Input, UpdateSourceNodeBodyTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        /**
         * Full replacement. Every field the caller wants to keep must appear here.
         * Nullable so callers who want to restore a past revision (see
         * [restoreFromRevisionIndex]) don't have to pass a dummy body. Exactly
         * one of [body] / [restoreFromRevisionIndex] must be set.
         */
        val body: JsonObject? = null,
        /**
         * Restore an earlier body from this node's history — 0 = most-recent
         * overwritten revision, 1 = the one before that, and so on. The
         * restored body replaces the current body; the *previous* (pre-
         * restore) current body is appended to history by the existing
         * post-mutate hook, so the audit log keeps a forward arrow of time
         * (no rewriting the past). Out-of-range and empty-history cases fail
         * loud with a hint directing to `source_query(select=history)`.
         * Mutually exclusive with [body].
         */
        val restoreFromRevisionIndex: Int? = null,
    )

    /**
     * Tolerant deserializer that rescues the "flattened body" shape some LLMs emit — instead
     * of nesting body fields under `body`, they splat them at the top level alongside
     * projectId/nodeId:
     *
     *   BAD  {"projectId":"p","nodeId":"shot-1","framing":"wide","dialogue":"…"}
     *   GOOD {"projectId":"p","nodeId":"shot-1","body":{"framing":"wide","dialogue":"…"}}
     *
     * Production logs on gpt-5.4-mini showed this failure fire repeatedly even after the
     * schema + helpText explicitly flagged it — the model just drops the body wrapper in
     * long contexts. `JsonConfig.ignoreUnknownKeys=true` made it worse: the flattened fields
     * silently disappeared and the only feedback was `MissingFieldException` for `body`.
     *
     * Transform-on-deserialize fixes it: if the incoming object has no `body` key (or `body`
     * isn't an object), fold every non-{projectId,nodeId,body} top-level entry into a
     * synthesized body before the standard data-class deserializer runs. Callers that pass
     * the correct nested shape are unaffected.
     */
    internal object InputCompatSerializer :
        JsonTransformingSerializer<Input>(Input.serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement {
            val obj = element as? JsonObject ?: return element
            if (obj["body"] is JsonObject) return element
            // Restore-by-index calls legitimately pass no `body`; don't fold
            // whatever other top-level keys happen to be there into a
            // synthesized body in that case — that's how the rescue fires a
            // false positive. Only synthesize when at least one non-reserved
            // top-level key exists (the "flattened body" shape).
            val reserved = setOf("projectId", "nodeId", "body", "restoreFromRevisionIndex")
            val flatKeys = obj.keys - reserved
            if (flatKeys.isEmpty()) return element
            val body = buildJsonObject {
                obj.forEach { (k, v) ->
                    if (k !in reserved) put(k, v)
                }
            }
            return buildJsonObject {
                obj["projectId"]?.let { put("projectId", it) }
                obj["nodeId"]?.let { put("nodeId", it) }
                obj["restoreFromRevisionIndex"]?.let { put("restoreFromRevisionIndex", it) }
                put("body", body)
            }
        }
    }

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
        /**
         * VISION §5.5 auto-regen hint: non-null when the project has any
         * stale clips after this edit. See [AutoRegenHint] for semantics.
         */
        val autoRegenHint: AutoRegenHint? = null,
    )

    override val id: String = "update_source_node_body"
    override val helpText: String =
        "Replace a source node's body wholesale (full replacement, no partial-patch). " +
            "Kind-agnostic. Does NOT touch kind (rebuild if kind must change), parents (use " +
            "set_source_node_parents), or id (use source_node_action(action=\"rename\")). " +
            "Bumps contentHash " +
            "→ bound clips go stale; run find_stale_clips after. Workflow: describe_source_" +
            "node first to read current body, mutate client-side (keep every field you want — " +
            "this is not a patch), pass complete object as `body`. Empty body is rejected. " +
            "Rollback: restoreFromRevisionIndex=N (0=newest) restores from history."
    override val inputSerializer: KSerializer<Input> = InputCompatSerializer
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

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
                    "Complete new body — full replacement, not a partial patch. Required " +
                        "unless restoreFromRevisionIndex is set (mutually exclusive).",
                )
                put("minProperties", 1)
                put("additionalProperties", true)
                put(
                    "examples",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("framing", JsonPrimitive("close-up"))
                                put("dialogue", JsonPrimitive("Where are we?"))
                                put("duration_seconds", JsonPrimitive(2.5))
                            },
                        )
                    },
                )
            }
            putJsonObject("restoreFromRevisionIndex") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "Restore body from history (0=newest). Mutually exclusive with body.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId")),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val nodeId = SourceNodeId(input.nodeId)

        // Exactly-one-of validation: either supply a body, or supply a
        // revision index to restore. Both means "which one wins?" — fail
        // loud. Neither is an obviously-broken call — same.
        val hasBody = input.body != null
        val hasRestore = input.restoreFromRevisionIndex != null
        if (hasBody && hasRestore) {
            error(
                "update_source_node_body takes exactly one of `body` / " +
                    "`restoreFromRevisionIndex` (got both). Drop one; restore picks the body " +
                    "from history, body passes an explicit new state.",
            )
        }
        if (!hasBody && !hasRestore) {
            error(
                "update_source_node_body requires either `body` (full replacement) or " +
                    "`restoreFromRevisionIndex` (past revision index, 0=newest historical). " +
                    "Call source_query(select=history, root=${nodeId.value}) to discover " +
                    "available indices.",
            )
        }

        // Resolve the effective new body. For restore: fetch history,
        // bounds-check the index, return the historical body. For direct
        // body: pass through unchanged.
        val effectiveNewBody: JsonElement = if (hasRestore) {
            val idx = input.restoreFromRevisionIndex!!
            if (idx < 0) {
                error(
                    "restoreFromRevisionIndex must be non-negative (got $idx). " +
                        "0 = newest historical revision; larger N goes further back.",
                )
            }
            // Fetch a window large enough to cover the requested index. The
            // store caps at 100 internally; we pass idx+1 so a legitimate
            // index==0 doesn't round-trip the full 20-default window.
            val window = projects.listSourceNodeHistory(pid, nodeId, limit = idx + 1)
            if (window.isEmpty()) {
                error(
                    "Source node ${nodeId.value} has no body-history entries — nothing to " +
                        "restore. Either this node was never updated, or the bundle was " +
                        "created before body-history tracking landed. Call source_query" +
                        "(select=history, root=${nodeId.value}) to confirm.",
                )
            }
            if (idx >= window.size) {
                // window.size reflects the capped fetch; re-fetch a wider
                // window to report the true ceiling accurately, but only
                // when the initial fetch maxed out (else window.size IS
                // the truth).
                val trueWindow = if (window.size >= idx + 1) window else
                    projects.listSourceNodeHistory(pid, nodeId, limit = 100)
                error(
                    "restoreFromRevisionIndex=$idx is out of range for node ${nodeId.value} " +
                        "(only ${trueWindow.size} historical revision(s) available; valid " +
                        "indices 0..${trueWindow.size - 1}). Call source_query(select=history, " +
                        "root=${nodeId.value}) to see all revisions.",
                )
            }
            window[idx].body
        } else {
            input.body!!
        }

        var previousHash = ""
        var previousBody: JsonElement = JsonObject(emptyMap())
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
            previousBody = existing.body
            kindSeen = existing.kind
            source.replaceNode(nodeId) { node ->
                // Only JsonObject bodies fit SourceNode.body's runtime contract
                // (the type is JsonElement but every kind-handler expects an
                // object today). A restored BodyRevision may technically be
                // any JsonElement; guard-cast here so a malformed restore
                // fails loud instead of silently corrupting downstream.
                val bodyAsObject = effectiveNewBody as? JsonObject
                    ?: error(
                        "restored body is not a JSON object (got ${effectiveNewBody::class.simpleName}). " +
                            "History entry is corrupt or was written by an older schema.",
                    )
                node.copy(body = bodyAsObject)
            }
        }

        // Append the pre-edit body to the per-node history log AFTER the
        // mutation lands, so a crashed mutate can't leave a false-positive
        // revision. Best-effort — ProjectStore swallows FS failures (see
        // FileProjectStore.appendSourceNodeHistory) because history is an
        // audit log, not canonical state.
        //
        // Restore calls still append: the pre-restore current body is the
        // new "most-recent overwritten" revision, so audit log keeps a
        // forward arrow of time. Compare vs. `effectiveNewBody` (not
        // `input.body`, which is null on restore) to catch no-op restores
        // where the current body equals the restored revision.
        if (previousBody != effectiveNewBody) {
            projects.appendSourceNodeHistory(
                pid,
                nodeId,
                BodyRevision(
                    body = previousBody,
                    overwrittenAtEpochMs = clock.now().toEpochMilliseconds(),
                ),
            )
        }

        newHash = updated.source.byId[nodeId]!!.contentHash
        boundClipCount = updated.timeline.tracks.sumOf { track ->
            track.clips.count { nodeId in it.sourceBinding }
        }

        val hint = updated.autoRegenHint()
        val staleHint = if (boundClipCount == 0) {
            " No clips bind this node directly; DAG descendants unchanged (body edits don't rewrite " +
                "parent refs). Call find_stale_clips if you want to surface transitively-stale clips."
        } else {
            " $boundClipCount clip(s) bind this node directly — they will show up as stale in " +
                "find_stale_clips until re-rendered."
        }
        val regenNudge = if (hint != null) {
            " autoRegenHint: ${hint.staleClipCount} clip(s) need regeneration — suggested next: ${hint.suggestedTool}."
        } else {
            ""
        }

        return ToolResult(
            title = "update source body for ${input.nodeId}",
            outputForLlm = "Replaced body of ${input.nodeId} (${kindSeen}). " +
                "contentHash $previousHash → $newHash.$staleHint$regenNudge",
            data = Output(
                projectId = input.projectId,
                nodeId = input.nodeId,
                kind = kindSeen,
                previousContentHash = previousHash,
                newContentHash = newHash,
                boundClipCount = boundClipCount,
                autoRegenHint = hint,
            ),
        )
    }
}
