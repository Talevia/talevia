package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
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
import kotlin.time.Duration

/**
 * Drop a single track (and, with `force=true`, every clip on it). The complement
 * to `add_track`.
 *
 * Timeline authoring verbs already cover `add_track` / `add_clip` / `remove_clip`
 * / `move_clip` / `clear_timeline`, but none of them drop a single track. Two
 * concrete gaps were left:
 *
 *  - Orphan empty tracks — the agent might `add_track` in anticipation of
 *    picture-in-picture or a music stem and then decide not to populate it.
 *    Without this tool the agent can't clean up its own skeleton; the user is
 *    left looking at a phantom track.
 *  - Targeted demolition — `clear_timeline` nukes every track; `remove_clip` per
 *    clip then leaves the empty track behind. "Drop the whole B-roll track" was
 *    previously unexpressible in a single call.
 *
 * Default `force=false` protects against accidental loss: if the track has clips,
 * the tool throws with the clip count and instructs the caller to pass
 * `force=true` or remove clips first. Empty tracks drop cleanly either way.
 *
 * `Timeline.duration` is recomputed from the remaining clips' `timeRange.end` —
 * matching the `AddClipTool` / `MoveClipToTrackTool` / `TrimClipTool` convention
 * — so dropping the track that held the tail clip shrinks the reported duration.
 *
 * Permission tier matches `clear_timeline` (`project.destructive`). Even the
 * "empty track" case counts as destructive because the agent may have declared
 * the track with downstream intent (user was told about it; subsequent tool
 * calls might reference its id).
 *
 * Emits a `Part.TimelineSnapshot` so `revert_timeline` can roll the drop — clips
 * and track alike — back in one step.
 */
class RemoveTrackTool(
    private val store: ProjectStore,
) : Tool<RemoveTrackTool.Input, RemoveTrackTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val trackId: String,
        /**
         * Drop the track even when it holds clips (every clip on it is discarded
         * too). Default `false` throws if the track is non-empty.
         */
        val force: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val trackId: String,
        val trackKind: String,
        val droppedClipCount: Int,
    )

    override val id: String = "remove_track"
    override val helpText: String =
        "Drop a single track from the timeline. Non-empty tracks require force=true (protects " +
            "against accidental loss); empty tracks drop cleanly either way. Force=true discards " +
            "every clip on the track as well. Other tracks are untouched. Emits a timeline " +
            "snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("trackId") { put("type", "string") }
            putJsonObject("force") {
                put("type", "boolean")
                put(
                    "description",
                    "Drop the track even if it holds clips (discards those clips). Default false.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("trackId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var trackKind = ""
        var droppedClips = 0
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val target = project.timeline.tracks.firstOrNull { it.id.value == input.trackId }
                ?: error("trackId '${input.trackId}' not found in project ${input.projectId}")
            if (target.clips.isNotEmpty() && !input.force) {
                error(
                    "track '${input.trackId}' has ${target.clips.size} clip(s); pass force=true to " +
                        "drop the track and its clips, or remove the clips first",
                )
            }
            trackKind = when (target) {
                is Track.Video -> "video"
                is Track.Audio -> "audio"
                is Track.Subtitle -> "subtitle"
                is Track.Effect -> "effect"
            }
            droppedClips = target.clips.size
            val newTracks = project.timeline.tracks.filter { it.id.value != input.trackId }
            val duration = newTracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(
                timeline = project.timeline.copy(tracks = newTracks, duration = duration),
            )
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "remove $trackKind track ${input.trackId}",
            outputForLlm = "Removed $trackKind track ${input.trackId} from project ${input.projectId}" +
                (if (droppedClips > 0) " (dropped $droppedClips clip(s))" else "") +
                ". Timeline snapshot: ${snapshotId.value}",
            data = Output(
                projectId = input.projectId,
                trackId = input.trackId,
                trackKind = trackKind,
                droppedClipCount = droppedClips,
            ),
        )
    }
}
