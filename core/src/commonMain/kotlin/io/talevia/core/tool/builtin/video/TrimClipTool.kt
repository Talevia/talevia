package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
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

/**
 * Re-trim a media-backed clip after creation. The agent currently has no way to
 * adjust a clip's `sourceRange` without deleting and re-adding (which loses any
 * downstream filters/transforms attached to the clip id), so trim_clip closes
 * an obvious editing primitive — a video editor without "trim" isn't one.
 *
 * Vocabulary mirrors [AddClipTool]: callers express the new state in absolute
 * `newSourceStartSeconds` / `newDurationSeconds` rather than deltas. Absolute
 * is more agent-friendly (no need to read the clip first to compute a delta)
 * and matches the rest of the timeline tools.
 *
 * Semantics:
 *  - `timeRange.start` is preserved. The clip stays anchored at the same
 *    timeline position; if the user wants to shift it, they chain `move_clip`.
 *    Coupling reposition into trim would conflate two intents.
 *  - `newDurationSeconds` becomes both `timeRange.duration` AND
 *    `sourceRange.duration` (clips play 1x speed; we don't yet model
 *    speed changes).
 *  - Either field may be omitted; omitted = preserve current.
 *  - Rejects [Clip.Text] — it has no `sourceRange` to trim. Use
 *    `add_subtitle` / `add_subtitles` to adjust subtitle timing for now;
 *    a dedicated text-trim tool would have a different shape.
 *
 * Validates against the bound asset's duration so we never produce a
 * `sourceRange` extending past the source media. Emits a
 * `Part.TimelineSnapshot` post-mutation so `revert_timeline` can undo.
 */
class TrimClipTool(
    private val store: ProjectStore,
    private val media: MediaStorage,
) : Tool<TrimClipTool.Input, TrimClipTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipId: String,
        /** New `sourceRange.start` in seconds. If null, keep current. */
        val newSourceStartSeconds: Double? = null,
        /**
         * New duration applied to BOTH `timeRange` and `sourceRange`. If null,
         * keep current `sourceRange.duration` (which equals current
         * `timeRange.duration`).
         */
        val newDurationSeconds: Double? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val newSourceStartSeconds: Double,
        val newDurationSeconds: Double,
        val newTimelineEndSeconds: Double,
    )

    override val id = "trim_clip"
    override val helpText =
        "Re-trim a video or audio clip's source range and/or duration without " +
            "removing/re-adding (which would lose attached filters). Preserves the " +
            "clip's timeline.start — chain `move_clip` if you also want to reposition. " +
            "At least one of newSourceStartSeconds / newDurationSeconds must be set. " +
            "Subtitle (Text) clips are not trimmable here; adjust them via add_subtitle. " +
            "Emits a timeline snapshot for revert_timeline."
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
            putJsonObject("newSourceStartSeconds") {
                put("type", "number")
                put("description", "New trim offset into the source media (>= 0). Omit to keep current.")
            }
            putJsonObject("newDurationSeconds") {
                put("type", "number")
                put("description", "New duration in seconds (> 0). Applied to both timeRange and sourceRange. Omit to keep current.")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("clipId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        if (input.newSourceStartSeconds == null && input.newDurationSeconds == null) {
            error("trim_clip requires at least one of newSourceStartSeconds / newDurationSeconds.")
        }
        input.newSourceStartSeconds?.let {
            require(it >= 0.0) { "newSourceStartSeconds must be >= 0 (got $it)" }
        }
        input.newDurationSeconds?.let {
            require(it > 0.0) { "newDurationSeconds must be > 0 (got $it)" }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        var foundTrackId: String? = null
        var resolvedSourceStart: Duration = Duration.ZERO
        var resolvedDuration: Duration = Duration.ZERO
        var resolvedTimelineEnd: Duration = Duration.ZERO
        val updated = store.mutate(pid) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    val (assetId, currentSourceRange) = when (target) {
                        is Clip.Video -> target.assetId to target.sourceRange
                        is Clip.Audio -> target.assetId to target.sourceRange
                        is Clip.Text -> error(
                            "trim_clip cannot trim a text/subtitle clip (clip ${input.clipId}); " +
                                "use add_subtitle to reset its time range.",
                        )
                    }
                    val newSourceStart: Duration = input.newSourceStartSeconds?.seconds
                        ?: currentSourceRange.start
                    val newDuration: Duration = input.newDurationSeconds?.seconds
                        ?: currentSourceRange.duration
                    // Validate against asset bounds. We need the asset duration
                    // to refuse trims that extend past source media. Looking it
                    // up here (inside the mutation block) means we do at most
                    // one round-trip per call.
                    val assetDuration = lookupAssetDuration(assetId)
                    require(newSourceStart < assetDuration) {
                        "newSourceStartSeconds ${newSourceStart.toDouble(DurationUnit.SECONDS)} " +
                            "exceeds asset duration ${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                    }
                    require(newSourceStart + newDuration <= assetDuration) {
                        "trim window (start=${newSourceStart.toDouble(DurationUnit.SECONDS)}s + " +
                            "duration=${newDuration.toDouble(DurationUnit.SECONDS)}s) extends past " +
                            "asset duration ${assetDuration.toDouble(DurationUnit.SECONDS)}s."
                    }
                    foundTrackId = track.id.value
                    resolvedSourceStart = newSourceStart
                    resolvedDuration = newDuration
                    val timelineRange = TimeRange(target.timeRange.start, newDuration)
                    val sourceRange = TimeRange(newSourceStart, newDuration)
                    resolvedTimelineEnd = timelineRange.end
                    val trimmed = withTrim(target, timelineRange, sourceRange)
                    val rebuilt = track.clips.map { if (it.id == target.id) trimmed else it }
                        .sortedBy { it.timeRange.start }
                    when (track) {
                        is Track.Video -> track.copy(clips = rebuilt)
                        is Track.Audio -> track.copy(clips = rebuilt)
                        is Track.Subtitle -> track.copy(clips = rebuilt)
                        is Track.Effect -> track.copy(clips = rebuilt)
                    }
                }
            }
            if (foundTrackId == null) {
                error("clip ${input.clipId} not found in project ${pid.value}")
            }
            val duration = newTracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end }
                ?: Duration.ZERO
            project.copy(
                timeline = project.timeline.copy(tracks = newTracks, duration = duration),
            )
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val sourceStartSec = resolvedSourceStart.toDouble(DurationUnit.SECONDS)
        val durationSec = resolvedDuration.toDouble(DurationUnit.SECONDS)
        val timelineEndSec = resolvedTimelineEnd.toDouble(DurationUnit.SECONDS)
        return ToolResult(
            title = "trim clip ${input.clipId}",
            outputForLlm = "Trimmed clip ${input.clipId} on track $foundTrackId to " +
                "sourceStart=${sourceStartSec}s, duration=${durationSec}s " +
                "(timeline end now ${timelineEndSec}s). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                newSourceStartSeconds = sourceStartSec,
                newDurationSeconds = durationSec,
                newTimelineEndSeconds = timelineEndSec,
            ),
        )
    }

    private suspend fun lookupAssetDuration(assetId: AssetId): Duration {
        val asset = media.get(assetId)
            ?: error("Asset ${assetId.value} bound to clip not found in MediaStorage.")
        return asset.metadata.duration
    }

    private fun withTrim(c: Clip, timelineRange: TimeRange, sourceRange: TimeRange): Clip = when (c) {
        is Clip.Video -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Audio -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
        is Clip.Text -> error("trim_clip cannot trim a text clip")
    }
}
