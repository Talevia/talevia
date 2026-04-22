package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Clone a clip to a new timeline position, preserving everything *except*
 * the clip id and the start time. Filters, transforms, source bindings,
 * volume, fade envelope, text body and style — all copied byte-for-byte.
 *
 * `add_clip` can mount the same asset twice, but it throws away the
 * attached state (filters, transforms, bindings). "Put the intro again at
 * 00:30, same look" is a common request that otherwise requires:
 *   1. add_clip(asset, @00:30) to get a fresh placement
 *   2. apply_filter / set_clip_transform / set_clip_volume / …
 *      repeated N times to recreate the original's attachments
 *
 * This tool collapses that into one call. The source binding set is
 * preserved so staleness-tracking continues to work on the duplicate
 * (they share the same upstream source nodes — changes to "Mei" still
 * invalidate both copies).
 *
 * Optional [Input.trackId] lets the caller place the duplicate on a
 * different track, provided the kinds match (Video→Video, Audio→Audio,
 * Text→Subtitle or Effect). Cross-kind moves are refused — the clip
 * data model can't survive the transition. Omit `trackId` to place on
 * the source clip's current track (the 95% case).
 *
 * No overlap validation — same Timeline contract as [MoveClipTool].
 * Emits a timeline snapshot for revert_timeline.
 */
@OptIn(ExperimentalUuidApi::class)
class DuplicateClipTool(
    private val store: ProjectStore,
) : Tool<DuplicateClipTool.Input, DuplicateClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** New `timeRange.start` in seconds. Must be >= 0. Duration is preserved. */
        val timelineStartSeconds: Double,
        /** Optional target track id. Must be the same kind as the source. Omit to duplicate on the same track. */
        val trackId: String? = null,
    )

    @Serializable data class Output(
        val originalClipId: String,
        val newClipId: String,
        val sourceTrackId: String,
        val targetTrackId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
    )

    override val id: String = "duplicate_clip"
    override val helpText: String =
        "Clone a clip (filters, transforms, source bindings, audio envelope, text style) " +
            "to a new timeline position with a fresh clip id. Optional trackId moves the " +
            "duplicate to another track of the same kind. Emits a timeline snapshot."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("timelineStartSeconds") {
                put("type", "number")
                put("description", "New timeline start position in seconds (must be >= 0).")
            }
            putJsonObject("trackId") {
                put("type", "string")
                put("description", "Optional target track id of the same kind. Defaults to the source clip's track.")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("clipId"),
                    JsonPrimitive("timelineStartSeconds"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.timelineStartSeconds >= 0.0) {
            "timelineStartSeconds must be >= 0 (got ${input.timelineStartSeconds})"
        }
        val newStart: Duration = input.timelineStartSeconds.seconds
        val newClipId = ClipId(Uuid.random().toString())

        var sourceTrackId: String? = null
        var sourceTrackKind: String? = null
        var newEndSeconds = 0.0

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val originalTrack = project.timeline.tracks.firstOrNull { t ->
                t.clips.any { it.id.value == input.clipId }
            } ?: error("clip ${input.clipId} not found in project ${input.projectId}")
            val original = originalTrack.clips.first { it.id.value == input.clipId }
            sourceTrackId = originalTrack.id.value
            sourceTrackKind = trackKindOf(originalTrack)

            val targetTrack = if (input.trackId == null || input.trackId == originalTrack.id.value) {
                originalTrack
            } else {
                val candidate = project.timeline.tracks.firstOrNull { it.id.value == input.trackId }
                    ?: error("trackId ${input.trackId} not found in project ${input.projectId}")
                require(trackKindOf(candidate) == trackKindOf(originalTrack)) {
                    "trackId ${input.trackId} is ${trackKindOf(candidate)} but source clip is ${trackKindOf(originalTrack)}"
                }
                candidate
            }

            val cloned = cloneClip(original, newClipId, newStart)
            newEndSeconds = (newStart + original.timeRange.duration).toDouble(DurationUnit.SECONDS)

            val tracks = project.timeline.tracks.map { t ->
                if (t.id == targetTrack.id) {
                    val newClips = (t.clips + cloned).sortedBy { it.timeRange.start }
                    withClips(t, newClips)
                } else {
                    t
                }
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val finalTrack = updated.timeline.tracks.first { t -> t.clips.any { it.id == newClipId } }
        val out = Output(
            originalClipId = input.clipId,
            newClipId = newClipId.value,
            sourceTrackId = sourceTrackId!!,
            targetTrackId = finalTrack.id.value,
            timelineStartSeconds = input.timelineStartSeconds,
            timelineEndSeconds = newEndSeconds,
        )
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "duplicate clip ${input.clipId} → ${input.timelineStartSeconds}s",
            outputForLlm = "Duplicated clip ${input.clipId} (${sourceTrackKind}) as " +
                "${out.newClipId} on track ${out.targetTrackId} " +
                "(${out.timelineStartSeconds}s..${out.timelineEndSeconds}s). Timeline snapshot: ${snapshotId.value}",
            data = out,
        )
    }

    private fun cloneClip(original: Clip, newId: ClipId, newStart: Duration): Clip = when (original) {
        is Clip.Video -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
        is Clip.Audio -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
        is Clip.Text -> original.copy(
            id = newId,
            timeRange = TimeRange(newStart, original.timeRange.duration),
        )
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun withClips(track: Track, clips: List<Clip>): Track = when (track) {
        is Track.Video -> track.copy(clips = clips)
        is Track.Audio -> track.copy(clips = clips)
        is Track.Subtitle -> track.copy(clips = clips)
        is Track.Effect -> track.copy(clips = clips)
    }
}
