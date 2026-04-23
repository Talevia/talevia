package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
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
 * Clone one or many clips to new timeline positions atomically, preserving
 * every attached field except the id and the start time. Per-item shape:
 * each entry carries its own clipId + target timeline-start + optional target
 * track.
 *
 * Optional target track must match the source clip's kind (Video→Video,
 * Audio→Audio, Text→Subtitle or Effect). Cross-kind moves rejected loudly.
 *
 * No overlap validation — same Timeline contract as move_clips. All-or-nothing;
 * one snapshot per call.
 */
@OptIn(ExperimentalUuidApi::class)
class DuplicateClipTool(
    private val store: ProjectStore,
) : Tool<DuplicateClipTool.Input, DuplicateClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val timelineStartSeconds: Double,
        val trackId: String? = null,
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
        val originalClipId: String,
        val newClipId: String,
        val sourceTrackId: String,
        val targetTrackId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "duplicate_clips"
    override val helpText: String =
        "Clone one or many clips (filters, transforms, source bindings, audio envelope, text " +
            "style) to new timeline positions atomically, each with a fresh clip id. Optional " +
            "per-item trackId moves the duplicate to another track of the same kind. " +
            "All-or-nothing; one snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
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
                put("description", "Clip duplications. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") {
                            put("type", "number")
                            put("description", "New timeline start position in seconds (must be >= 0).")
                        }
                        putJsonObject("trackId") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional target track id of the same kind. Defaults to the source clip's track.",
                            )
                        }
                    }
                    put(
                        "required",
                        JsonArray(
                            listOf(
                                JsonPrimitive("clipId"),
                                JsonPrimitive("timelineStartSeconds"),
                            ),
                        ),
                    )
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
            require(item.timelineStartSeconds >= 0.0) {
                "items[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got ${item.timelineStartSeconds})"
            }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val originalTrack = tracks.firstOrNull { t ->
                    t.clips.any { it.id.value == item.clipId }
                } ?: error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val original = originalTrack.clips.first { it.id.value == item.clipId }

                val targetTrack = if (item.trackId == null || item.trackId == originalTrack.id.value) {
                    originalTrack
                } else {
                    val candidate = tracks.firstOrNull { it.id.value == item.trackId }
                        ?: error("items[$idx] (${item.clipId}): trackId ${item.trackId} not found in project ${pid.value}")
                    require(trackKindOf(candidate) == trackKindOf(originalTrack)) {
                        "items[$idx] (${item.clipId}): trackId ${item.trackId} is ${trackKindOf(candidate)} " +
                            "but source clip is ${trackKindOf(originalTrack)}"
                    }
                    candidate
                }

                val newStart: Duration = item.timelineStartSeconds.seconds
                val newClipId = ClipId(Uuid.random().toString())
                val cloned = cloneClip(original, newClipId, newStart)
                val newEndSeconds = (newStart + original.timeRange.duration).toDouble(DurationUnit.SECONDS)

                tracks = tracks.map { t ->
                    if (t.id == targetTrack.id) {
                        val newClips = (t.clips + cloned).sortedBy { it.timeRange.start }
                        withClips(t, newClips)
                    } else {
                        t
                    }
                }
                results += ItemResult(
                    originalClipId = item.clipId,
                    newClipId = newClipId.value,
                    sourceTrackId = originalTrack.id.value,
                    targetTrackId = targetTrack.id.value,
                    timelineStartSeconds = item.timelineStartSeconds,
                    timelineEndSeconds = newEndSeconds,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "duplicate × ${results.size}",
            outputForLlm = "Duplicated ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
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
