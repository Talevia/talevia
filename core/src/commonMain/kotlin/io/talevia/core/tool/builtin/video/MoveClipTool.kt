package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
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

/**
 * Reposition a clip on the timeline by id — change its `timeRange.start`
 * while preserving its duration and `sourceRange` (the clip plays the same
 * material, just at a different timeline time).
 *
 * The system prompt promises this primitive (e.g. "if you want ripple-delete
 * behavior, follow up with `move_clip` on each downstream clip") so its
 * absence was a credibility gap — the LLM was being told to call a tool that
 * didn't exist. Same-track only: cross-track moves change the rendering
 * semantics (different track stack ordering, different filter pipeline) and
 * deserve their own tool when a real driver appears.
 *
 * No overlap validation. The Timeline allows overlapping clips on a track —
 * picture-in-picture, transitions, layered effects all rely on it. Refusing
 * to move into an overlap would block legitimate workflows; the agent is
 * expected to know what it's doing or the user will see the result and
 * iterate.
 *
 * Emits a `Part.TimelineSnapshot` post-mutation so `revert_timeline` can
 * roll the move back — same pattern as every other timeline-mutating tool.
 */
class MoveClipTool(
    private val store: ProjectStore,
) : Tool<MoveClipTool.Input, MoveClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** New `timeRange.start` in seconds. Must be >= 0. Duration is preserved. */
        val newStartSeconds: Double,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
    )

    override val id = "move_clip"
    override val helpText =
        "Reposition a clip on the timeline by id. Changes the clip's start time " +
            "while preserving its duration and source range. Same-track only — " +
            "use this to chain ripple-delete or shift a clip earlier/later. " +
            "Emits a timeline snapshot so revert_timeline can undo the move."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("newStartSeconds") {
                put("type", "number")
                put("description", "New timeline start position in seconds (must be >= 0).")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("clipId"),
                    JsonPrimitive("newStartSeconds"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        if (input.newStartSeconds < 0) {
            error("newStartSeconds must be >= 0 (got ${input.newStartSeconds})")
        }
        var foundTrackId: String? = null
        var oldStartSeconds = 0.0
        val newStart = input.newStartSeconds.seconds
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    foundTrackId = track.id.value
                    oldStartSeconds = target.timeRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)
                    val moved = withTimeRange(target, TimeRange(newStart, target.timeRange.duration))
                    val rebuilt = track.clips.map { if (it.id == target.id) moved else it }
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
                error("clip ${input.clipId} not found in project ${input.projectId}")
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "move clip ${input.clipId} → ${input.newStartSeconds}s",
            outputForLlm = "Moved clip ${input.clipId} on track $foundTrackId from " +
                "${oldStartSeconds}s to ${input.newStartSeconds}s. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                oldStartSeconds = oldStartSeconds,
                newStartSeconds = input.newStartSeconds,
            ),
        )
    }

    private fun withTimeRange(c: io.talevia.core.domain.Clip, range: TimeRange): io.talevia.core.domain.Clip =
        when (c) {
            is io.talevia.core.domain.Clip.Video -> c.copy(timeRange = range)
            is io.talevia.core.domain.Clip.Audio -> c.copy(timeRange = range)
            is io.talevia.core.domain.Clip.Text -> c.copy(timeRange = range)
        }
}
