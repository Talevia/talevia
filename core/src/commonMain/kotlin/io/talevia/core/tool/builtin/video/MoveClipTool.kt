package io.talevia.core.tool.builtin.video

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

/**
 * Reposition one or many clips on the timeline by id — atomically. Per-item
 * shape: each entry carries its own clipId + optional timelineStartSeconds +
 * optional toTrackId. At least one of the two must be set per item.
 *
 * Three per-item combinations:
 *  - `timelineStartSeconds != null, toTrackId == null` — same-track time shift.
 *  - `timelineStartSeconds == null, toTrackId != null` — cross-track move at
 *    the same time.
 *  - both set — cross-track AND reposition.
 *
 * Target track kind must match the clip kind (Video→Video, Audio→Audio,
 * Text→Subtitle). Track.Effect is not a valid target.
 *
 * No overlap validation — the Timeline deliberately allows overlapping clips.
 * Everything else on the clip (sourceRange, filters, transforms, sourceBinding)
 * is preserved. All-or-nothing; one snapshot per call.
 */
class MoveClipTool(
    private val store: ProjectStore,
) : Tool<MoveClipTool.Input, MoveClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val timelineStartSeconds: Double? = null,
        val toTrackId: String? = null,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val items: List<Item>,
    )

    @Serializable data class ItemResult(
        val clipId: String,
        val fromTrackId: String,
        val toTrackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
        val changedTrack: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "move_clips"
    override val helpText =
        "Reposition one or many clips on the timeline atomically. Each item carries its own " +
            "clipId + optional timelineStartSeconds (time shift) and/or toTrackId (cross-track). " +
            "At least one per item. Target track must match the clip kind (video/audio/subtitle). " +
            "Preserves duration, sourceRange, filters, transforms, source bindings. All-or-nothing. " +
            "One snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

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
            putJsonObject("items") {
                put("type", "array")
                put("description", "Move operations to apply. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") {
                            put("type", "number")
                            put(
                                "description",
                                "New timeline start position in seconds (>= 0). Omit to keep current " +
                                    "(valid only when toTrackId is set).",
                            )
                        }
                        putJsonObject("toTrackId") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional target track id. Omit for same-track reposition. Must be " +
                                    "same kind as the clip.",
                            )
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("clipId"))))
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("items"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.items.isNotEmpty()) { "items must not be empty" }
        input.items.forEachIndexed { idx, item ->
            if (item.timelineStartSeconds == null && item.toTrackId == null) {
                error("items[$idx] (${item.clipId}): at least one of timelineStartSeconds / toTrackId must be set")
            }
            item.timelineStartSeconds?.let {
                if (it < 0.0) error("items[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got $it)")
            }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val sourceHit = tracks
                    .firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it } }
                    ?: error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (sourceTrack, clip) = sourceHit

                val targetTrack: Track = when {
                    item.toTrackId == null || item.toTrackId == sourceTrack.id.value -> sourceTrack
                    else -> tracks.firstOrNull { it.id.value == item.toTrackId }
                        ?: error("items[$idx] (${item.clipId}): target track ${item.toTrackId} not found")
                }

                if (targetTrack.id != sourceTrack.id && !isKindCompatible(clip, targetTrack)) {
                    error(
                        "items[$idx] (${item.clipId}): cannot move ${clipKind(clip)} clip onto " +
                            "${trackKind(targetTrack)} track ${targetTrack.id.value}.",
                    )
                }

                val oldStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS)
                val newStart: Duration = item.timelineStartSeconds?.seconds ?: clip.timeRange.start
                val newStartSeconds = newStart.toDouble(DurationUnit.SECONDS)
                val moved = withTimeRange(clip, TimeRange(newStart, clip.timeRange.duration))

                tracks = if (targetTrack.id == sourceTrack.id) {
                    tracks.map { track ->
                        if (track.id != sourceTrack.id) track else replaceClip(track, moved)
                    }
                } else {
                    tracks.map { track ->
                        when (track.id) {
                            sourceTrack.id -> removeClip(track, clip.id.value)
                            targetTrack.id -> addClip(track, moved)
                            else -> track
                        }
                    }
                }

                results += ItemResult(
                    clipId = item.clipId,
                    fromTrackId = sourceTrack.id.value,
                    toTrackId = targetTrack.id.value,
                    oldStartSeconds = oldStartSeconds,
                    newStartSeconds = newStartSeconds,
                    changedTrack = sourceTrack.id.value != targetTrack.id.value,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "move ${results.size} clip(s)",
            outputForLlm = "Moved ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
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
