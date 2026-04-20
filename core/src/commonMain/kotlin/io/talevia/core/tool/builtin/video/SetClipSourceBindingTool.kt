package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
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
 * Rebind an existing clip to a new set of [io.talevia.core.domain.source.SourceNode] ids,
 * or clear the binding entirely. Closes the VISION §4 "professional path" gap where
 * `sourceBinding` is populated at creation time (by `add_clip` or the AIGC pipeline copying
 * from the lockfile) but has no post-hoc mutation path: a professional user wanting to
 * retroactively tie a hand-authored clip to a `character_ref`, or swap which upstream
 * source nodes a clip depends on, would otherwise have to delete and re-add the clip —
 * which drops the id, orphans any downstream tool state, and resets transforms / filters.
 *
 * Semantics:
 *  - `sourceBinding` is the full replacement set. An empty list clears the binding
 *    (valid — matches the "hand-authored, not incremental-compile eligible" state
 *    documented on [Clip.sourceBinding]).
 *  - Every provided id must resolve in `project.source.byId`. Unknown ids fail-loud with
 *    the full missing set so the agent can decide whether to `add_source_node` first
 *    or drop the offending id.
 *  - Works uniformly across all three Clip variants (Video / Audio / Text) — each has its
 *    own `copy()` because they're sealed subclasses, so the replacement walks a
 *    `when`-expression.
 *  - Everything else on the clip — timeRange, sourceRange, filters, transforms, asset,
 *    volume, text, style — is preserved. This is a pure rebind.
 *  - Emits a `Part.TimelineSnapshot` post-mutation so `revert_timeline` can undo, matching
 *    every other timeline-mutating tool.
 */
class SetClipSourceBindingTool(
    private val store: ProjectStore,
) : Tool<SetClipSourceBindingTool.Input, SetClipSourceBindingTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** Full replacement set of source-node ids. Empty list clears the binding. */
        val sourceBinding: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val clipId: String,
        val previousBinding: List<String>,
        val newBinding: List<String>,
    )

    override val id: String = "set_clip_source_binding"
    override val helpText: String =
        "Replace (or clear) an existing clip's sourceBinding — the set of source-node ids " +
            "it derives from. Use this to retroactively tie a hand-authored clip to a " +
            "character_ref, or swap which upstream nodes a clip depends on, without losing " +
            "the clip id, filters, or transforms. Empty list clears the binding."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("sourceBinding") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Full replacement set of source-node ids. Empty list clears the binding.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("clipId"),
                    JsonPrimitive("sourceBinding"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var previousBinding: List<String> = emptyList()
        val newBindingSet = input.sourceBinding.map { SourceNodeId(it) }.toSet()

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val missing = newBindingSet.filter { it !in project.source.byId }
            require(missing.isEmpty()) {
                "unknown source node ids: ${missing.joinToString(", ") { it.value }}"
            }

            val target = project.timeline.tracks
                .flatMap { track -> track.clips.map { track to it } }
                .firstOrNull { (_, clip) -> clip.id.value == input.clipId }
                ?: error("clip ${input.clipId} not found in project ${input.projectId}")
            val (sourceTrack, clip) = target
            previousBinding = clip.sourceBinding.map { it.value }.sorted()

            val rebound: Clip = when (clip) {
                is Clip.Video -> clip.copy(sourceBinding = newBindingSet)
                is Clip.Audio -> clip.copy(sourceBinding = newBindingSet)
                is Clip.Text -> clip.copy(sourceBinding = newBindingSet)
            }

            val newTracks = project.timeline.tracks.map { track ->
                if (track.id == sourceTrack.id) replaceClip(track, clip, rebound) else track
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val newBindingList = newBindingSet.map { it.value }.sorted()
        return ToolResult(
            title = "rebind clip ${input.clipId}",
            outputForLlm = "Rebound clip ${input.clipId} from [${previousBinding.joinToString(", ")}] " +
                "to [${newBindingList.joinToString(", ")}]. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                projectId = input.projectId,
                clipId = input.clipId,
                previousBinding = previousBinding,
                newBinding = newBindingList,
            ),
        )
    }

    private fun replaceClip(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
