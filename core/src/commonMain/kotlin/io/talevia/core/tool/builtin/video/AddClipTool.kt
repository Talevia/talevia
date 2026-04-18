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
    override val description = "Append a video clip to the project timeline (optionally on a specific track and at a specific time)."
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
        val asset = media.get(AssetId(input.assetId))
            ?: error("Asset ${input.assetId} not found; import_media first.")

        val sourceStart: Duration = input.sourceStartSeconds.seconds
        val clipDuration: Duration = (input.durationSeconds?.seconds ?: (asset.metadata.duration - sourceStart))
            .coerceAtMost(asset.metadata.duration - sourceStart)

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val (videoTrack, otherTracks) = pickVideoTrack(project.timeline.tracks, input.trackId)
            val tlStart = input.timelineStartSeconds?.seconds ?: videoTrack.clips.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            val newClip = Clip.Video(
                id = ClipId(Uuid.random().toString()),
                timeRange = TimeRange(tlStart, clipDuration),
                sourceRange = TimeRange(sourceStart, clipDuration),
                assetId = asset.id,
            )
            val newClips = (videoTrack.clips + newClip).sortedBy { it.timeRange.start }
            val newTrack = (videoTrack as Track.Video).copy(clips = newClips)
            val tracks = otherTracks + newTrack
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val addedTrack = updated.timeline.tracks.first { input.trackId == null || it.id.value == input.trackId } as Track.Video
        val addedClip = addedTrack.clips.maxBy { it.timeRange.end } as Clip.Video
        val out = Output(
            clipId = addedClip.id.value,
            timelineStartSeconds = addedClip.timeRange.start.toDouble(DurationUnit.SECONDS),
            timelineEndSeconds = addedClip.timeRange.end.toDouble(DurationUnit.SECONDS),
            trackId = addedTrack.id.value,
        )
        return ToolResult(
            title = "add clip @ ${formatSeconds(out.timelineStartSeconds)}s",
            outputForLlm = "Added clip ${out.clipId} on track ${out.trackId} (${out.timelineStartSeconds}s..${out.timelineEndSeconds}s)",
            data = out,
        )
    }

    private fun formatSeconds(s: Double): String {
        val cs = (s * 100).toLong()
        return "${cs / 100}.${(cs % 100).toString().padStart(2, '0')}"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun pickVideoTrack(tracks: List<Track>, requestedId: String?): Pair<Track, List<Track>> {
        val match = if (requestedId != null) tracks.firstOrNull { it.id.value == requestedId && it is Track.Video }
        else tracks.firstOrNull { it is Track.Video }
        return if (match != null) {
            match to tracks.filter { it.id != match.id }
        } else {
            val newTrack = Track.Video(TrackId(Uuid.random().toString()))
            newTrack to tracks
        }
    }
}
