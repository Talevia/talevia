package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.TrackId
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
 * Append one or many video clips to the project timeline atomically. Per-item
 * shape: each entry carries its own assetId + optional placement fields, so a
 * single call can "append these 5 clips end-to-end on the main track" in one
 * atomic edit.
 *
 * Placement per item:
 *  - `timelineStartSeconds != null` — place at the given time.
 *  - `timelineStartSeconds == null` — append after the last clip currently on
 *    the target track. Within a batch, subsequent items see each other's
 *    appends (so N append-style items lay end-to-end).
 *  - `trackId` optional — omit for the first video track (created if absent).
 *
 * All-or-nothing; one snapshot per call.
 */
@OptIn(ExperimentalUuidApi::class)
class AddClipTool(
    private val store: ProjectStore,
) : Tool<AddClipTool.Input, AddClipTool.Output> {

    @Serializable data class Item(
        val assetId: String,
        val timelineStartSeconds: Double? = null,
        val sourceStartSeconds: Double = 0.0,
        val durationSeconds: Double? = null,
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
        val clipId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
        val trackId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "add_clips"
    override val helpText =
        "Append one or many video clips to the project timeline atomically. Each item is " +
            "{ assetId, timelineStartSeconds?, sourceStartSeconds?, durationSeconds?, trackId? }. " +
            "Omitted timelineStartSeconds means 'append after the last clip on the target track'. " +
            "Within a batch subsequent appends see each other. All-or-nothing; one snapshot per call."
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
                put("description", "Clips to add. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("assetId") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") {
                            put("type", "number")
                            put("description", "If omitted, append after the last clip on the track.")
                        }
                        putJsonObject("sourceStartSeconds") {
                            put("type", "number")
                            put("description", "Trim offset into the source media.")
                        }
                        putJsonObject("durationSeconds") {
                            put("type", "number")
                            put("description", "If omitted, use the asset's full remaining duration.")
                        }
                        putJsonObject("trackId") {
                            put("type", "string")
                            put("description", "Optional track to add to; otherwise the first Video track (created if absent).")
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
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
            require(item.sourceStartSeconds >= 0.0) {
                "items[$idx] (asset ${item.assetId}): sourceStartSeconds must be >= 0"
            }
            item.timelineStartSeconds?.let {
                require(it >= 0.0) {
                    "items[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
                }
            }
            item.durationSeconds?.let {
                require(it > 0.0) {
                    "items[$idx] (asset ${item.assetId}): durationSeconds must be > 0"
                }
            }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val asset = project.assets.firstOrNull { it.id.value == item.assetId }
                    ?: error("items[$idx]: asset ${item.assetId} not found; import_media first.")
                val sourceStart: Duration = item.sourceStartSeconds.seconds
                val remaining = asset.metadata.duration - sourceStart
                require(sourceStart < asset.metadata.duration) {
                    "items[$idx] (asset ${item.assetId}): sourceStartSeconds ${item.sourceStartSeconds} exceeds asset " +
                        "duration ${asset.metadata.duration.inWholeMilliseconds / 1000.0}s"
                }
                val clipDuration: Duration = (item.durationSeconds?.seconds ?: remaining).coerceAtMost(remaining)
                require(clipDuration > Duration.ZERO) { "items[$idx] (asset ${item.assetId}): clip duration must be > 0" }

                val videoTrack = pickVideoTrack(tracks, item.trackId)
                val tlStart = item.timelineStartSeconds?.seconds
                    ?: videoTrack.clips.maxOfOrNull { it.timeRange.end }
                    ?: Duration.ZERO
                require(tlStart >= Duration.ZERO) {
                    "items[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
                }

                val clipId = ClipId(Uuid.random().toString())
                val newClip = Clip.Video(
                    id = clipId,
                    timeRange = TimeRange(tlStart, clipDuration),
                    sourceRange = TimeRange(sourceStart, clipDuration),
                    assetId = asset.id,
                )
                val newClips = (videoTrack.clips + newClip).sortedBy { it.timeRange.start }
                val newTrack = videoTrack.copy(clips = newClips)
                tracks = upsertTrackPreservingOrder(tracks, newTrack)

                results += ItemResult(
                    clipId = clipId.value,
                    timelineStartSeconds = tlStart.toDouble(DurationUnit.SECONDS),
                    timelineEndSeconds = (tlStart + clipDuration).toDouble(DurationUnit.SECONDS),
                    trackId = newTrack.id.value,
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "add ${results.size} clip(s)",
            outputForLlm = "Added ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }

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
