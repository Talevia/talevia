package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AddClipTool(
    private val store: ProjectStore,
    private val media: MediaStorage,
) : Tool<AddClipTool.Input, AddClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val assetId: String,
        val timelineStartSeconds: Double? = null,
        val sourceStartSeconds: Double = 0.0,
        val durationSeconds: Double? = null,
        val trackId: String? = null,
    )
    @Serializable data class Output(
        val clipId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
        val trackId: String,
    )

    override val id = "add_clip"
    override val helpText = "Append a video clip to the project timeline (optionally on a specific track and at a specific time)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("assetId") { put("type", "string") }
            putJsonObject("timelineStartSeconds") { put("type", "number"); put("description", "If omitted, append after the last clip on the track.") }
            putJsonObject("sourceStartSeconds") { put("type", "number"); put("description", "Trim offset into the source media.") }
            putJsonObject("durationSeconds") { put("type", "number"); put("description", "If omitted, use the asset's full remaining duration.") }
            putJsonObject("trackId") { put("type", "string"); put("description", "Optional track to add to; otherwise the first Video track (created if absent).") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.sourceStartSeconds >= 0.0) { "sourceStartSeconds must be >= 0" }
        input.timelineStartSeconds?.let {
            require(it >= 0.0) { "timelineStartSeconds must be >= 0" }
        }
        input.durationSeconds?.let {
            require(it > 0.0) { "durationSeconds must be > 0" }
        }

        val asset = media.get(AssetId(input.assetId))
            ?: error("Asset ${input.assetId} not found; import_media first.")

        val sourceStart: Duration = input.sourceStartSeconds.seconds
        val remaining = asset.metadata.duration - sourceStart
        require(sourceStart < asset.metadata.duration) {
            "sourceStartSeconds ${input.sourceStartSeconds} exceeds asset duration ${asset.metadata.duration.inWholeMilliseconds / 1000.0}s"
        }
        val clipDuration: Duration = (input.durationSeconds?.seconds ?: remaining).coerceAtMost(remaining)
        require(clipDuration > Duration.ZERO) { "clip duration must be > 0" }
        val clipId = ClipId(Uuid.random().toString())

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val videoTrack = pickVideoTrack(project.timeline.tracks, input.trackId)
            val tlStart = input.timelineStartSeconds?.seconds ?: videoTrack.clips.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            require(tlStart >= Duration.ZERO) { "timelineStartSeconds must be >= 0" }
            val newClip = Clip.Video(
                id = clipId,
                timeRange = TimeRange(tlStart, clipDuration),
                sourceRange = TimeRange(sourceStart, clipDuration),
                assetId = asset.id,
            )
            val newClips = (videoTrack.clips + newClip).sortedBy { it.timeRange.start }
            val newTrack = videoTrack.copy(clips = newClips)
            val tracks = upsertTrackPreservingOrder(project.timeline.tracks, newTrack)
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val addedTrack = updated.timeline.tracks.firstOrNull { track ->
            track is Track.Video && track.clips.any { it.id == clipId }
        } as? Track.Video ?: error("Added clip $clipId not found after mutation")
        val addedClip = addedTrack.clips.firstOrNull { it.id == clipId } as? Clip.Video
            ?: error("Added clip $clipId not found on track ${addedTrack.id.value}")
        val out = Output(
            clipId = addedClip.id.value,
            timelineStartSeconds = addedClip.timeRange.start.toDouble(DurationUnit.SECONDS),
            timelineEndSeconds = addedClip.timeRange.end.toDouble(DurationUnit.SECONDS),
            trackId = addedTrack.id.value,
        )
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "add clip @ ${formatSeconds(out.timelineStartSeconds)}s",
            outputForLlm = "Added clip ${out.clipId} on track ${out.trackId} (${out.timelineStartSeconds}s..${out.timelineEndSeconds}s). Timeline snapshot: ${snapshotId.value}",
            data = out,
        )
    }

    private fun formatSeconds(s: Double): String {
        val cs = (s * 100).toLong()
        return "${cs / 100}.${(cs % 100).toString().padStart(2, '0')}"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun pickVideoTrack(tracks: List<Track>, requestedId: String?): Track.Video {
        val match = if (requestedId != null) {
            val requested = tracks.firstOrNull { it.id.value == requestedId }
                ?: error("trackId $requestedId not found")
            if (requested !is Track.Video) {
                error("trackId $requestedId is not a video track")
            }
            requested
        } else {
            tracks.firstOrNull { it is Track.Video }
        }
        return match as? Track.Video ?: Track.Video(TrackId(Uuid.random().toString()))
    }
}
