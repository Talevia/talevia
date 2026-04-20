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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Cross-track companion to [MoveClipTool]. Takes a clip off its current
 * track and inserts it onto a different track of the **same kind**, optionally
 * at a new start time. Closes the gap that [MoveClipTool] flags in its kdoc:
 * "same-track only: cross-track moves change the rendering semantics... and
 * deserve their own tool when a real driver appears."
 *
 * Real drivers: layering video for picture-in-picture (move from main video
 * track to an overlay track), separating dialogue from music (move an audio
 * clip from a mixed track to a dedicated music track), reordering subtitle
 * priority (move a Text clip to a different Subtitle track).
 *
 * Semantics:
 *  - `newStartSeconds` is optional — when omitted, the clip keeps its
 *    existing `timeRange.start`. Callers who want "move and shift"
 *    pass both. Callers who want "same time, new track" pass only
 *    `targetTrackId`.
 *  - The target track must exist. A future variant could auto-create
 *    tracks on the fly, but that conflates two operations and blinds
 *    the agent to the track explosion footgun.
 *  - The target track kind must match the clip kind:
 *      Clip.Video → Track.Video
 *      Clip.Audio → Track.Audio
 *      Clip.Text  → Track.Subtitle
 *    Mismatched kinds fail-loud; rendering semantics don't survive
 *    e.g. a Video clip on an Audio track.
 *  - If the target track is the source track, the call is refused with
 *    a pointer to `move_clip`. Silently succeeding would hide the fact
 *    that the agent probably had a different intent.
 *  - `sourceRange`, filters, transforms, binding — everything else on
 *    the clip is preserved. This is a pure cross-track reposition.
 *  - Emits a `Part.TimelineSnapshot` post-mutation so `revert_timeline`
 *    can undo — matches every other timeline-mutating tool.
 *
 * Track.Effect is not a valid target in either direction — it's an overlay
 * lane for effect clips and its concrete clip-kind contract is not pinned
 * yet. When someone needs it, they'll flesh out the Effect clip type first.
 */
class MoveClipToTrackTool(
    private val store: ProjectStore,
) : Tool<MoveClipToTrackTool.Input, MoveClipToTrackTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val targetTrackId: String,
        /** Optional new timeline start. When null, clip keeps its current start. */
        val newStartSeconds: Double? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val fromTrackId: String,
        val toTrackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
    )

    override val id: String = "move_clip_to_track"
    override val helpText: String =
        "Move a clip onto a different track of the same kind (video/audio/subtitle). " +
            "Optional newStartSeconds also shifts the clip; omit to keep its current start. " +
            "Use this for PIP layering, splitting dialogue onto its own audio track, subtitle " +
            "priority reordering. For same-track repositioning, use move_clip instead."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("targetTrackId") { put("type", "string"); put("description", "Existing track id of the same kind as the clip.") }
            putJsonObject("newStartSeconds") {
                put("type", "number")
                put("description", "Optional new timeline start (>= 0). When omitted, clip keeps its current start.")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("clipId"),
                    JsonPrimitive("targetTrackId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        input.newStartSeconds?.let {
            require(it >= 0.0) { "newStartSeconds must be >= 0 (got $it)" }
        }

        var sourceTrackId: String? = null
        var oldStartSeconds = 0.0
        var newStartSeconds = 0.0

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val target = project.timeline.tracks
                .flatMap { track -> track.clips.map { track to it } }
                .firstOrNull { (_, clip) -> clip.id.value == input.clipId }
                ?: error("clip ${input.clipId} not found in project ${input.projectId}")
            val (sourceTrack, clip) = target
            sourceTrackId = sourceTrack.id.value

            val targetTrack = project.timeline.tracks.firstOrNull { it.id.value == input.targetTrackId }
                ?: error("target track ${input.targetTrackId} not found in project ${input.projectId}")

            if (targetTrack.id.value == sourceTrack.id.value) {
                error(
                    "target track ${input.targetTrackId} is the clip's current track. " +
                        "Use move_clip for same-track repositioning.",
                )
            }

            val compatible = when (clip) {
                is Clip.Video -> targetTrack is Track.Video
                is Clip.Audio -> targetTrack is Track.Audio
                is Clip.Text -> targetTrack is Track.Subtitle
            }
            if (!compatible) {
                val clipKind = when (clip) {
                    is Clip.Video -> "video"
                    is Clip.Audio -> "audio"
                    is Clip.Text -> "text"
                }
                val trackKind = when (targetTrack) {
                    is Track.Video -> "video"
                    is Track.Audio -> "audio"
                    is Track.Subtitle -> "subtitle"
                    is Track.Effect -> "effect"
                }
                error(
                    "cannot move $clipKind clip onto $trackKind track ${input.targetTrackId}. " +
                        "Video clips need a video track, audio → audio, text → subtitle.",
                )
            }

            oldStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS)
            val newStart = input.newStartSeconds?.seconds ?: clip.timeRange.start
            newStartSeconds = newStart.toDouble(DurationUnit.SECONDS)
            val moved = withTimeRange(clip, TimeRange(newStart, clip.timeRange.duration))

            val newTracks = project.timeline.tracks.map { track ->
                when {
                    track.id == sourceTrack.id -> removeClip(track, clip.id.value)
                    track.id == targetTrack.id -> addClip(track, moved)
                    else -> track
                }
            }
            val duration = newTracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: kotlin.time.Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = newTracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "move clip ${input.clipId} → track ${input.targetTrackId}",
            outputForLlm = "Moved clip ${input.clipId} from track $sourceTrackId to ${input.targetTrackId} " +
                "(start ${oldStartSeconds}s → ${newStartSeconds}s). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                fromTrackId = sourceTrackId!!,
                toTrackId = input.targetTrackId,
                oldStartSeconds = oldStartSeconds,
                newStartSeconds = newStartSeconds,
            ),
        )
    }

    private fun withTimeRange(clip: Clip, range: TimeRange): Clip = when (clip) {
        is Clip.Video -> clip.copy(timeRange = range)
        is Clip.Audio -> clip.copy(timeRange = range)
        is Clip.Text -> clip.copy(timeRange = range)
    }

    private fun removeClip(track: Track, clipId: String): Track {
        val remaining = track.clips.filterNot { it.id.value == clipId }
        return when (track) {
            is Track.Video -> track.copy(clips = remaining)
            is Track.Audio -> track.copy(clips = remaining)
            is Track.Subtitle -> track.copy(clips = remaining)
            is Track.Effect -> track.copy(clips = remaining)
        }
    }

    private fun addClip(track: Track, clip: Clip): Track {
        val added = (track.clips + clip).sortedBy { it.timeRange.start }
        return when (track) {
            is Track.Video -> track.copy(clips = added)
            is Track.Audio -> track.copy(clips = added)
            is Track.Subtitle -> track.copy(clips = added)
            is Track.Effect -> track.copy(clips = added)
        }
    }
}
