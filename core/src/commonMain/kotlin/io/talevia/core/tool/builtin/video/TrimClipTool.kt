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
 * Re-trim one or many media-backed clips after creation in a single atomic edit.
 * Per-item shape: each entry carries its own clipId + optional new source-range
 * start / duration.
 *
 * Semantics:
 *  - `timeRange.start` is preserved per clip. Chain `move_clips` if reposition is
 *    wanted too.
 *  - `newDurationSeconds` becomes both `timeRange.duration` AND `sourceRange.duration`.
 *  - Either item-level field may be omitted; omitted = preserve current.
 *  - Rejects [Clip.Text] (no sourceRange). Use `add_subtitles` to retime subtitles.
 *  - Validates against the bound asset's duration.
 *  - All-or-nothing. One snapshot per call.
 */
class TrimClipTool(
    private val store: ProjectStore,
) : Tool<TrimClipTool.Input, TrimClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val newSourceStartSeconds: Double? = null,
        val newDurationSeconds: Double? = null,
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
        val trackId: String,
        val newSourceStartSeconds: Double,
        val newDurationSeconds: Double,
        val newTimelineEndSeconds: Double,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "trim_clips"
    override val helpText =
        "Re-trim one or many video/audio clips' source range and/or duration atomically without " +
            "removing/re-adding (which would lose attached filters). Preserves each clip's " +
            "timeline.start — chain `move_clips` if you also want to reposition. Each item must " +
            "set at least one of newSourceStartSeconds / newDurationSeconds. Text clips not " +
            "supported here; use add_subtitles. All-or-nothing; one snapshot per call."
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
                put("description", "Trim operations. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("newSourceStartSeconds") {
                            put("type", "number")
                            put("description", "New trim offset into the source media (>= 0). Omit to keep current.")
                        }
                        putJsonObject("newDurationSeconds") {
                            put("type", "number")
                            put("description", "New duration in seconds (> 0). Applied to both timeRange and sourceRange. Omit to keep current.")
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
            if (item.newSourceStartSeconds == null && item.newDurationSeconds == null) {
                error("items[$idx] (${item.clipId}): at least one of newSourceStartSeconds / newDurationSeconds required")
            }
            item.newSourceStartSeconds?.let {
                require(it >= 0.0) { "items[$idx] (${item.clipId}): newSourceStartSeconds must be >= 0 (got $it)" }
            }
            item.newDurationSeconds?.let {
                require(it > 0.0) { "items[$idx] (${item.clipId}): newDurationSeconds must be > 0 (got $it)" }
            }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val hit = tracks.firstNotNullOfOrNull { t ->
                    t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it }
                } ?: error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (track, clip) = hit

                val (assetId, currentSourceRange) = when (clip) {
                    is Clip.Video -> clip.assetId to clip.sourceRange
                    is Clip.Audio -> clip.assetId to clip.sourceRange
                    is Clip.Text -> error(
                        "items[$idx] (${item.clipId}): trim_clips cannot trim a text/subtitle clip; " +
                            "use add_subtitles to reset its time range.",
                    )
                }
                val newSourceStart: Duration = item.newSourceStartSeconds?.seconds ?: currentSourceRange.start
                val newDuration: Duration = item.newDurationSeconds?.seconds ?: currentSourceRange.duration
                val assetDuration = project.assets.firstOrNull { it.id == assetId }?.metadata?.duration
                    ?: error(
                        "items[$idx] (${item.clipId}): asset ${assetId.value} bound to clip not found in project " +
                            "${pid.value} — import it (or restore the clip's source binding) first.",
                    )
                require(newSourceStart < assetDuration) {
                    "items[$idx] (${item.clipId}): newSourceStartSeconds ${newSourceStart.toDouble(DurationUnit.SECONDS)} " +
                        "exceeds asset duration ${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                }
                require(newSourceStart + newDuration <= assetDuration) {
                    "items[$idx] (${item.clipId}): trim window (start=${newSourceStart.toDouble(DurationUnit.SECONDS)}s + " +
                        "duration=${newDuration.toDouble(DurationUnit.SECONDS)}s) extends past " +
                        "asset duration ${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                }
                val timelineRange = TimeRange(clip.timeRange.start, newDuration)
                val sourceRange = TimeRange(newSourceStart, newDuration)
                val trimmed = withTrim(clip, timelineRange, sourceRange)
                val rebuilt = track.clips.map { if (it.id == clip.id) trimmed else it }
                    .sortedBy { it.timeRange.start }
                val newTrack = when (track) {
                    is Track.Video -> track.copy(clips = rebuilt)
                    is Track.Audio -> track.copy(clips = rebuilt)
                    is Track.Subtitle -> track.copy(clips = rebuilt)
                    is Track.Effect -> track.copy(clips = rebuilt)
                }
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += ItemResult(
                    clipId = item.clipId,
                    trackId = track.id.value,
                    newSourceStartSeconds = newSourceStart.toDouble(DurationUnit.SECONDS),
                    newDurationSeconds = newDuration.toDouble(DurationUnit.SECONDS),
                    newTimelineEndSeconds = timelineRange.end.toDouble(DurationUnit.SECONDS),
                )
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "trim ${results.size} clip(s)",
            outputForLlm = "Trimmed ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }

    private fun withTrim(c: Clip, timelineRange: TimeRange, sourceRange: TimeRange): Clip = when (c) {
        is Clip.Video -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Audio -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Text -> error("trim_clips cannot trim a text clip")
    }
}
