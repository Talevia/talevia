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

/**
 * Delete a clip from the timeline by id (the missing primitive from the
 * cut/stitch/filter/transition lineup the agent uses to *edit* — VISION §1
 * "传统剪辑与特效渲染（cut / stitch / filter / transition / OpenGL shader / 合成）").
 *
 * Until this tool landed, the agent could `add_clip` / `replace_clip` /
 * `split_clip` but had no way to *remove* one. The only workaround was
 * `revert_timeline` to a prior snapshot, which discards every later edit too —
 * a bulldozer where a scalpel was needed. This tool closes that gap.
 *
 * Other clips' timeRanges are not adjusted ("ripple delete" semantics are not
 * applied) — the gap is left as-is so existing transitions / subtitles aligned
 * to specific timeline timestamps don't drift. If the user wants ripple-delete
 * behavior, they can chain a `move_clip` for each downstream clip; we'd rather
 * have the explicit two-step than silently shift unrelated clips.
 *
 * Emits a `Part.TimelineSnapshot` post-mutation so `revert_timeline` can roll
 * the deletion back — no data is permanently lost from the agent's POV.
 */
class RemoveClipTool(
    private val store: ProjectStore,
) : Tool<RemoveClipTool.Input, RemoveClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val remainingClipsOnTrack: Int,
    )

    override val id = "remove_clip"
    override val helpText =
        "Delete a clip from the timeline by id. Other clips are NOT shifted to fill the gap — " +
            "if you want ripple-delete behavior, follow up with move_clip on the downstream clips. " +
            "Emits a timeline snapshot so revert_timeline can undo the deletion."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var foundTrackId: String? = null
        var remaining = 0
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    foundTrackId = track.id.value
                    val keep = track.clips.filter { it.id.value != input.clipId }
                    remaining = keep.size
                    when (track) {
                        is Track.Video -> track.copy(clips = keep)
                        is Track.Audio -> track.copy(clips = keep)
                        is Track.Subtitle -> track.copy(clips = keep)
                        is Track.Effect -> track.copy(clips = keep)
                    }
                }
            }
            if (foundTrackId == null) {
                error("clip ${input.clipId} not found in project ${input.projectId}")
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "remove clip ${input.clipId}",
            outputForLlm = "Removed clip ${input.clipId} from track $foundTrackId " +
                "($remaining clip(s) remain on the track). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                remainingClipsOnTrack = remaining,
            ),
        )
    }
}
