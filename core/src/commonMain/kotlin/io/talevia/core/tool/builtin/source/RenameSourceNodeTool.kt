package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.emitTimelineSnapshot
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
 * Atomically rename a [SourceNode] by id — closes the VISION §3.4 "可读" leg and the
 * §5.1 "refactor without losing history" question. Without this tool the only way to
 * "rename" is `remove_source_node` + `define_*` under a new id, which drops every
 * downstream reference: sibling nodes' [SourceNode.parents], [Clip.sourceBinding]
 * entries, and lockfile bindings / content-hash snapshots. Giving the agent one
 * primitive that rewires all of those in a single mutation keeps the DAG, timeline,
 * and lockfile coherent through a pure renaming refactor.
 *
 * **Scope — structural only.** The rename rewrites ids at the Core / Source-DAG
 * level: the node's own `id`, every [SourceRef.nodeId] that pointed at the old id,
 * every clip's `sourceBinding` set, every [LockfileEntry.sourceBinding] set, and
 * every [LockfileEntry.sourceContentHashes] key. It does **not** look inside genre
 * typed bodies — if a `narrative.shot.body.sceneId` happens to reference the renamed
 * id as an opaque string, the caller is responsible for updating it via the
 * kind-specific `update_*` tool. The alternative (snooping typed fields) would
 * break the Core → genre boundary this project defends, so we carve it out
 * deliberately and document it in the help text.
 *
 * **contentHash.** [SourceNode.contentHash] is computed from `(kind, body, parents)`
 * — not from `id` — so the renamed node's hash is unchanged. Nodes whose `parents`
 * list contained `oldId` *do* get a new hash (the parent-ref value changes, the
 * serialised parents list changes, the hash changes). That cascade is the *correct*
 * stale-propagation behaviour: renaming a node is a refactor, and any downstream
 * AIGC render that consumed the old parent-ref hash should be invalidated.
 *
 * **Atomicity.** The rename runs in a single [ProjectStore.mutate] block so the
 * source / timeline / lockfile either all reflect the new id, or none of them do.
 * There is no intermediate state where `Source.byId` and `Clip.sourceBinding`
 * disagree about which id is canonical. When any clip binding was rewritten the
 * tool emits one [Part.TimelineSnapshot] so `revert_timeline` can unwind the
 * rename; when no clips were touched the snapshot would be identical to the
 * previous one, so we skip it to keep the undo stack tidy.
 *
 * **Permission.** `source.write` — same tier as `set_source_node_parents` /
 * `remove_source_node`. A rename has the same blast radius as a parent edit.
 */
class RenameSourceNodeTool(
    private val projects: ProjectStore,
) : Tool<RenameSourceNodeTool.Input, RenameSourceNodeTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val oldId: String,
        val newId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val oldId: String,
        val newId: String,
        /** Nodes whose [SourceNode.parents] list was rewritten. */
        val parentsRewrittenCount: Int,
        /** Clips whose [Clip.sourceBinding] set was rewritten. */
        val clipsRewrittenCount: Int,
        /** Lockfile entries whose `sourceBinding` / `sourceContentHashes` were rewritten. */
        val lockfileEntriesRewrittenCount: Int,
    )

    override val id: String = "rename_source_node"
    override val helpText: String =
        "Atomically rename a source node by id. Rewrites the node itself, every parent-ref on " +
            "descendant nodes, every clip.sourceBinding set, and every lockfile entry's " +
            "sourceBinding + sourceContentHashes keys in one mutation — no dangling references. " +
            "Does NOT rewrite string ids embedded inside typed bodies (e.g. narrative.shot.sceneId); " +
            "update those separately via the kind-specific update_* tool. newId must match the source-id " +
            "slug shape (lowercase letters / digits / '-'). Same-id is a no-op. Rejects loudly on " +
            "unknown oldId or newId collision."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("oldId") {
                put("type", "string")
                put("description", "Existing source node id to rename.")
            }
            putJsonObject("newId") {
                put("type", "string")
                put(
                    "description",
                    "New id for the node. Must be lowercase letters / digits / '-', non-empty, " +
                        "must not collide with an existing node in the project.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("oldId"),
                    JsonPrimitive("newId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val oldId = SourceNodeId(input.oldId)
        val newId = SourceNodeId(input.newId)

        // No-op — return without mutating (no revision bump, no snapshot).
        if (oldId == newId) {
            return ToolResult(
                title = "rename ${input.oldId} (no-op)",
                outputForLlm = "oldId == newId (${input.oldId}); no-op, project state untouched.",
                data = Output(
                    projectId = input.projectId,
                    oldId = input.oldId,
                    newId = input.newId,
                    parentsRewrittenCount = 0,
                    clipsRewrittenCount = 0,
                    lockfileEntriesRewrittenCount = 0,
                ),
            )
        }

        require(isValidSourceNodeIdSlug(input.newId)) {
            "newId '${input.newId}' is not a valid source-node id slug — must be non-empty, " +
                "lowercase ASCII letters / digits / '-' only, and not start or end with '-'."
        }

        var parentsRewritten = 0
        var clipsRewritten = 0
        var lockfileRewritten = 0

        val updated = projects.mutate(pid) { project ->
            val source = project.source
            if (source.byId[oldId] == null) {
                error(
                    "Source node ${input.oldId} not found in project ${input.projectId}. " +
                        "Call source_query(select=nodes) to discover available ids.",
                )
            }
            if (source.byId[newId] != null) {
                error(
                    "Source node ${input.newId} already exists in project ${input.projectId}; " +
                        "rename would collide. Pick a different newId or remove_source_node first.",
                )
            }

            val (newSource, parentsTouched) = renameInSource(source, oldId, newId)
            parentsRewritten = parentsTouched

            val (newTimeline, clipsTouched) = renameInTimeline(project.timeline, oldId, newId)
            clipsRewritten = clipsTouched

            val (newLockfile, entriesTouched) = renameInLockfile(project.lockfile, oldId, newId)
            lockfileRewritten = entriesTouched

            project.copy(
                source = newSource,
                timeline = newTimeline,
                lockfile = newLockfile,
            )
        }

        // Only emit a snapshot when the timeline actually changed — otherwise we'd
        // spam `revert_timeline` with a stream of identical snapshots.
        val snapshotIdNote = if (clipsRewritten > 0) {
            val partId = emitTimelineSnapshot(ctx, updated.timeline)
            " Timeline snapshot: ${partId.value}."
        } else {
            ""
        }

        return ToolResult(
            title = "rename source node ${input.oldId} -> ${input.newId}",
            outputForLlm = "Renamed source node ${input.oldId} to ${input.newId}. " +
                "Rewrote $parentsRewritten parent-ref(s), $clipsRewritten clip sourceBinding(s), " +
                "and $lockfileRewritten lockfile entry binding(s).$snapshotIdNote " +
                "Note: string ids inside typed bodies (e.g. narrative.shot.sceneId) are NOT touched — " +
                "update those via the kind-specific update_* tool if needed.",
            data = Output(
                projectId = input.projectId,
                oldId = input.oldId,
                newId = input.newId,
                parentsRewrittenCount = parentsRewritten,
                clipsRewrittenCount = clipsRewritten,
                lockfileEntriesRewrittenCount = lockfileRewritten,
            ),
        )
    }

    /**
     * Rewrite [oldId] → [newId] in the source DAG. Touches both the target node's own
     * `id` and every other node's `parents` list that referenced the old id. Recomputes
     * `contentHash` on touched rows via [SourceNode.create] so the stale-propagation
     * lane picks up the (correct) cascade on parent-ref changes.
     */
    private fun renameInSource(
        source: Source,
        oldId: SourceNodeId,
        newId: SourceNodeId,
    ): Pair<Source, Int> {
        var parentsTouched = 0
        val rewritten = source.nodes.map { node ->
            val isRenameTarget = node.id == oldId
            val hadOldParent = node.parents.any { it.nodeId == oldId }
            if (!isRenameTarget && !hadOldParent) {
                node
            } else {
                val nextParents = if (hadOldParent) {
                    parentsTouched += 1
                    node.parents.map { ref ->
                        if (ref.nodeId == oldId) SourceRef(newId) else ref
                    }
                } else {
                    node.parents
                }
                val nextId = if (isRenameTarget) newId else node.id
                // contentHash must be recomputed: for the target the (kind, body, parents)
                // tuple may be identical (hash stays), for a descendant the parents list
                // changed (hash bumps). SourceNode.create handles both.
                SourceNode.create(
                    id = nextId,
                    kind = node.kind,
                    body = node.body,
                    parents = nextParents,
                    revision = node.revision + 1,
                )
            }
        }
        return source.copy(
            revision = source.revision + 1,
            nodes = rewritten,
        ) to parentsTouched
    }

    /**
     * Rewrite [oldId] → [newId] in every clip's [Clip.sourceBinding] across every track.
     * Preserves clip order, track order, and all other clip fields. Count of *clips*
     * touched (not tracks).
     */
    private fun renameInTimeline(
        timeline: Timeline,
        oldId: SourceNodeId,
        newId: SourceNodeId,
    ): Pair<Timeline, Int> {
        var clipsTouched = 0
        val rewrittenTracks = timeline.tracks.map { track ->
            val rewrittenClips = track.clips.map { clip ->
                if (oldId !in clip.sourceBinding) {
                    clip
                } else {
                    clipsTouched += 1
                    val nextBinding = clip.sourceBinding.map { if (it == oldId) newId else it }.toSet()
                    when (clip) {
                        is Clip.Video -> clip.copy(sourceBinding = nextBinding)
                        is Clip.Audio -> clip.copy(sourceBinding = nextBinding)
                        is Clip.Text -> clip.copy(sourceBinding = nextBinding)
                    }
                }
            }
            when (track) {
                is Track.Video -> track.copy(clips = rewrittenClips)
                is Track.Audio -> track.copy(clips = rewrittenClips)
                is Track.Subtitle -> track.copy(clips = rewrittenClips)
                is Track.Effect -> track.copy(clips = rewrittenClips)
            }
        }
        return timeline.copy(tracks = rewrittenTracks) to clipsTouched
    }

    /**
     * Rewrite [oldId] → [newId] in every lockfile entry's `sourceBinding` set and
     * `sourceContentHashes` map key. Count of *entries* touched (an entry counts once
     * even if both fields contained the id).
     */
    private fun renameInLockfile(
        lockfile: Lockfile,
        oldId: SourceNodeId,
        newId: SourceNodeId,
    ): Pair<Lockfile, Int> {
        var touched = 0
        val rewritten = lockfile.entries.map { entry ->
            val inBinding = oldId in entry.sourceBinding
            val inHashes = oldId in entry.sourceContentHashes
            if (!inBinding && !inHashes) {
                entry
            } else {
                touched += 1
                val nextBinding = if (inBinding) {
                    entry.sourceBinding.map { if (it == oldId) newId else it }.toSet()
                } else {
                    entry.sourceBinding
                }
                val nextHashes = if (inHashes) {
                    entry.sourceContentHashes
                        .mapKeys { (k, _) -> if (k == oldId) newId else k }
                } else {
                    entry.sourceContentHashes
                }
                entry.copy(
                    sourceBinding = nextBinding,
                    sourceContentHashes = nextHashes,
                )
            }
        }
        return lockfile.copy(entries = rewritten) to touched
    }
}
