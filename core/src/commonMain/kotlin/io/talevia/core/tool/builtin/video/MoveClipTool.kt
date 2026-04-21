package io.talevia.core.tool.builtin.video

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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Reposition a clip on the timeline by id. Unifies "same-track time shift"
 * and "cross-track move" behind a single entry point so the LLM sees one
 * reposition verb instead of two.
 *
 * Three input combinations:
 *  - `timelineStartSeconds != null, toTrackId == null` — same-track time
 *    shift. The clip keeps its track; its `timeRange.start` becomes the new
 *    value. Duration + sourceRange preserved.
 *  - `timelineStartSeconds == null, toTrackId != null` — cross-track move
 *    at the same time. Target track kind must match the clip kind
 *    (Video→Video, Audio→Audio, Text→Subtitle).
 *  - `timelineStartSeconds != null, toTrackId != null` — cross-track AND
 *    reposition at a new time.
 *
 * Both fields null → fail-loud; an empty request is almost certainly a
 * typo rather than a legitimate no-op.
 *
 * `toTrackId` equal to the clip's current track is accepted (treated as
 * same-track): saves the LLM from having to branch on "am I currently on
 * that track or not". No-op on time is still a no-op though; the tool
 * reports `changedTrack=false, oldStartSeconds == newStartSeconds` so the
 * caller can see what happened.
 *
 * No overlap validation on the target track. The Timeline deliberately
 * allows overlapping clips (picture-in-picture, transitions, layered
 * effects rely on it). `sourceRange`, filters, transforms, sourceBinding
 * — everything else on the clip is preserved; this is a pure reposition.
 *
 * Track.Effect is not a valid `toTrackId` target: its concrete clip-kind
 * contract isn't pinned yet, so kind-compat rules are not defined.
 *
 * Emits a `Part.TimelineSnapshot` post-mutation so `revert_timeline` can
 * undo — matches every other timeline-mutating tool.
 */
class MoveClipTool(
    private val store: ProjectStore,
) : Tool<MoveClipTool.Input, MoveClipTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipId: String,
        /**
         * New `timeRange.start` in seconds. Null keeps the current start
         * (cross-track move only). When both this and `toTrackId` are null,
         * the call is rejected — one of them must be set.
         */
        val timelineStartSeconds: Double? = null,
        /**
         * Target track id. Null keeps the clip on its current track
         * (same-track reposition). Target must exist and match the clip
         * kind (Video→Video, Audio→Audio, Text→Subtitle). Equal to the
         * clip's current track is accepted (same-track path).
         */
        val toTrackId: String? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val fromTrackId: String,
        val toTrackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
        /** True when the resolved target track differs from the source track. */
        val changedTrack: Boolean,
    )

    override val id = "move_clip"
    override val helpText =
        "Reposition a clip on the timeline by id. Pass `timelineStartSeconds` to shift the clip " +
            "in time (same-track), `toTrackId` to move it onto a different track of the same " +
            "kind (video/audio/subtitle), or both to move and shift. At least one must be set. " +
            "Preserves duration, sourceRange, filters, transforms, and source bindings. " +
            "Cross-track kind mismatch (video→audio, text→video, anything→effect) fails loud. " +
            "Emits a timeline snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("timelineStartSeconds") {
                put("type", "number")
                put(
                    "description",
                    "New timeline start position in seconds (>= 0). Omit to keep the current start " +
                        "(valid only when toTrackId is set).",
                )
            }
            putJsonObject("toTrackId") {
                put("type", "string")
                put(
                    "description",
                    "Optional target track id. Omit for same-track reposition. The track must exist " +
                        "and be of the same kind as the clip (video/audio/subtitle).",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("clipId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        if (input.timelineStartSeconds == null && input.toTrackId == null) {
            error("move_clip requires at least one of timelineStartSeconds / toTrackId to be set.")
        }
        input.timelineStartSeconds?.let {
            if (it < 0.0) error("timelineStartSeconds must be >= 0 (got $it)")
        }

        val pid = ctx.resolveProjectId(input.projectId)
        var sourceTrackId: String? = null
        var resolvedTargetTrackId: String? = null
        var oldStartSeconds = 0.0
        var newStartSeconds = 0.0

        val updated = store.mutate(pid) { project ->
            val sourceHit = project.timeline.tracks
                .firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == input.clipId }?.let { t to it } }
                ?: error("clip ${input.clipId} not found in project ${pid.value}")
            val (sourceTrack, clip) = sourceHit
            sourceTrackId = sourceTrack.id.value

            val targetTrack: Track = when {
                input.toTrackId == null || input.toTrackId == sourceTrack.id.value -> sourceTrack
                else -> project.timeline.tracks.firstOrNull { it.id.value == input.toTrackId }
                    ?: error("target track ${input.toTrackId} not found in project ${pid.value}")
            }
            resolvedTargetTrackId = targetTrack.id.value

            if (targetTrack.id != sourceTrack.id && !isKindCompatible(clip, targetTrack)) {
                error(
                    "cannot move ${clipKind(clip)} clip onto ${trackKind(targetTrack)} track " +
                        "${targetTrack.id.value}. Video clips need a video track, audio → audio, " +
                        "text → subtitle.",
                )
            }

            oldStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS)
            val newStart: Duration = input.timelineStartSeconds?.seconds ?: clip.timeRange.start
            newStartSeconds = newStart.toDouble(DurationUnit.SECONDS)
            val moved = withTimeRange(clip, TimeRange(newStart, clip.timeRange.duration))

            val newTracks = if (targetTrack.id == sourceTrack.id) {
                // Same-track: replace in place, resort.
                project.timeline.tracks.map { track ->
                    if (track.id != sourceTrack.id) track
                    else replaceClip(track, moved)
                }
            } else {
                project.timeline.tracks.map { track ->
                    when (track.id) {
                        sourceTrack.id -> removeClip(track, clip.id.value)
                        targetTrack.id -> addClip(track, moved)
                        else -> track
                    }
                }
            }
            val duration = newTracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = newTracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val fromId = sourceTrackId!!
        val toId = resolvedTargetTrackId!!
        val changedTrack = fromId != toId
        val body = if (changedTrack) {
            "Moved clip ${input.clipId} from track $fromId to $toId (start ${oldStartSeconds}s → ${newStartSeconds}s). " +
                "Timeline snapshot: ${snapshotId.value}"
        } else {
            "Moved clip ${input.clipId} on track $fromId from ${oldStartSeconds}s to ${newStartSeconds}s. " +
                "Timeline snapshot: ${snapshotId.value}"
        }
        return ToolResult(
            title = if (changedTrack) "move clip ${input.clipId} → track $toId" else "move clip ${input.clipId} → ${newStartSeconds}s",
            outputForLlm = body,
            data = Output(
                clipId = input.clipId,
                fromTrackId = fromId,
                toTrackId = toId,
                oldStartSeconds = oldStartSeconds,
                newStartSeconds = newStartSeconds,
                changedTrack = changedTrack,
            ),
        )
    }

    private fun isKindCompatible(clip: Clip, track: Track): Boolean = when (clip) {
        is Clip.Video -> track is Track.Video
        is Clip.Audio -> track is Track.Audio
        is Clip.Text -> track is Track.Subtitle
    }

    private fun clipKind(clip: Clip): String = when (clip) {
        is Clip.Video -> "video"
        is Clip.Audio -> "audio"
        is Clip.Text -> "text"
    }

    private fun trackKind(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun withTimeRange(clip: Clip, range: TimeRange): Clip = when (clip) {
        is Clip.Video -> clip.copy(timeRange = range)
        is Clip.Audio -> clip.copy(timeRange = range)
        is Clip.Text -> clip.copy(timeRange = range)
    }

    private fun replaceClip(track: Track, replacement: Clip): Track {
        val rebuilt = track.clips.map { if (it.id == replacement.id) replacement else it }
            .sortedBy { it.timeRange.start }
        return when (track) {
            is Track.Video -> track.copy(clips = rebuilt)
            is Track.Audio -> track.copy(clips = rebuilt)
            is Track.Subtitle -> track.copy(clips = rebuilt)
            is Track.Effect -> track.copy(clips = rebuilt)
        }
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
