package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
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
 * By default, other clips' timeRanges are not adjusted — the gap is left as-is
 * so existing transitions / subtitles aligned to specific timeline timestamps
 * don't drift. Pass `ripple=true` to close the gap: every clip on the **same
 * track** whose `timeRange.start >= removed.timeRange.end` shifts left by the
 * removed clip's duration. Overlapping / layered clips on the same track
 * (`start < removed.end`) are left alone — they were intentionally placed to
 * overlap, and shifting them would destroy the overlap semantics. Other
 * tracks are never touched; if the user wants sequence-wide ripple, chain
 * this with `move_clip` on the sibling tracks (audio-video sync cases).
 *
 * Emits a single `Part.TimelineSnapshot` post-mutation so `revert_timeline` can
 * roll the deletion — ripple included — back in one step.
 */
class RemoveClipTool(
    private val store: ProjectStore,
) : Tool<RemoveClipTool.Input, RemoveClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /**
         * When `true`, close the gap on the removed clip's track by shifting
         * every later non-overlapping clip left by the removed clip's
         * duration. Default `false` preserves the historical "leave the gap"
         * semantics. Does NOT ripple across tracks — chain `move_clip` on
         * sibling tracks if sync requires it.
         */
        val ripple: Boolean = false,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val remainingClipsOnTrack: Int,
        val rippled: Boolean,
        val shiftedClipCount: Int,
        val shiftSeconds: Double,
    )

    override val id = "remove_clip"
    override val helpText =
        "Delete a clip from the timeline by id. Pass `ripple=true` to close the gap on the same " +
            "track (every later clip shifts left by the removed clip's duration); default `false` " +
            "leaves the gap so transitions / subtitles aligned to specific timestamps don't drift. " +
            "Ripple is single-track only — chain `move_clip` on other tracks when sync requires it. " +
            "Emits a timeline snapshot so revert_timeline can undo the deletion."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("ripple") {
                put("type", "boolean")
                put(
                    "description",
                    "Close the gap on the removed clip's track. Default false.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var foundTrackId: String? = null
        var removedRange: TimeRange? = null
        var shifted = 0
        var remaining = 0
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    foundTrackId = track.id.value
                    removedRange = target.timeRange
                    val keep = track.clips.filter { it.id.value != input.clipId }
                    val shiftedClips = if (input.ripple) {
                        keep.map { clip ->
                            if (clip.timeRange.start >= target.timeRange.end) {
                                shifted += 1
                                clip.shiftStart(-target.timeRange.duration)
                            } else {
                                clip
                            }
                        }
                    } else {
                        keep
                    }
                    remaining = shiftedClips.size
                    when (track) {
                        is Track.Video -> track.copy(clips = shiftedClips)
                        is Track.Audio -> track.copy(clips = shiftedClips)
                        is Track.Subtitle -> track.copy(clips = shiftedClips)
                        is Track.Effect -> track.copy(clips = shiftedClips)
                    }
                }
            }
            if (foundTrackId == null) {
                error("clip ${input.clipId} not found in project ${input.projectId}")
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val shiftSeconds = removedRange?.duration?.inWholeMilliseconds?.let { it / 1000.0 } ?: 0.0
        val rippleNote = if (input.ripple) " Rippled $shifted clip(s) left by ${shiftSeconds}s." else ""
        return ToolResult(
            title = "remove clip ${input.clipId}",
            outputForLlm = "Removed clip ${input.clipId} from track $foundTrackId " +
                "($remaining clip(s) remain on the track).$rippleNote Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                remainingClipsOnTrack = remaining,
                rippled = input.ripple,
                shiftedClipCount = shifted,
                shiftSeconds = shiftSeconds,
            ),
        )
    }

    /**
     * Shift a clip's [Clip.timeRange] by the given delta (`sourceRange` is
     * untouched — ripple changes WHEN the clip plays, not WHICH source bytes
     * it reads). Declared as a local helper so the ripple arm of `execute`
     * keeps its per-variant copy(...) clear.
     */
    private fun Clip.shiftStart(delta: kotlin.time.Duration): Clip {
        val newRange = timeRange.copy(start = timeRange.start + delta)
        return when (this) {
            is Clip.Video -> copy(timeRange = newRange)
            is Clip.Audio -> copy(timeRange = newRange)
            is Clip.Text -> copy(timeRange = newRange)
        }
    }
}
