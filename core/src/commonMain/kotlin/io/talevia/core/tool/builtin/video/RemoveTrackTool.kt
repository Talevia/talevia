package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.ProjectStore
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

/**
 * Drop one or many tracks (and, with `force=true`, every clip on them) in a
 * single atomic edit. The complement to `add_track`.
 *
 * Broadcast shape — a single shared `force` flag applies to the whole batch.
 * If any track is non-empty and `force=false`, the whole batch aborts with
 * the offending track's clip count so the agent can retry with `force=true`
 * or remove clips first.
 *
 * `Timeline.duration` is recomputed from the remaining clips' `timeRange.end`.
 * Permission tier matches `clear_timeline` (`project.destructive`) — even an
 * empty track counts as destructive because the agent may have declared the
 * track with downstream intent.
 *
 * Emits one `Part.TimelineSnapshot` so `revert_timeline` can roll the whole
 * batch back in one step.
 */
class RemoveTrackTool(
    private val store: ProjectStore,
) : Tool<RemoveTrackTool.Input, RemoveTrackTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val trackIds: List<String>,
        /**
         * Drop tracks even when they hold clips (every clip on them is
         * discarded too). Default `false` throws if any target track is
         * non-empty, leaving the whole batch untouched.
         */
        val force: Boolean = false,
    )

    @Serializable data class ItemResult(
        val trackId: String,
        val trackKind: String,
        val droppedClipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val forced: Boolean,
        val snapshotId: String,
    )

    override val id: String = "remove_tracks"
    override val helpText: String =
        "Drop one or many tracks from the timeline atomically. Non-empty tracks require " +
            "force=true; otherwise the whole batch aborts with the offending clip count. " +
            "Force=true discards every clip on the listed tracks. Other tracks untouched. " +
            "One timeline snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

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
            putJsonObject("trackIds") {
                put("type", "array")
                put("description", "Track ids to drop. At least one required.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("force") {
                put("type", "boolean")
                put(
                    "description",
                    "Drop tracks even if they hold clips (discards those clips). Default false.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("trackIds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.trackIds.isNotEmpty()) { "trackIds must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.trackIds.forEachIndexed { idx, trackId ->
                val target = tracks.firstOrNull { it.id.value == trackId }
                    ?: error("trackIds[$idx] '$trackId' not found in project ${pid.value}")
                if (target.clips.isNotEmpty() && !input.force) {
                    error(
                        "trackIds[$idx] '$trackId' has ${target.clips.size} clip(s); pass " +
                            "force=true to drop the track(s) and their clips, or remove the clips first",
                    )
                }
                val kind = trackKind(target)
                val droppedClips = target.clips.size
                tracks = tracks.filter { it.id.value != trackId }
                results += ItemResult(trackId = trackId, trackKind = kind, droppedClipCount = droppedClips)
            }
            val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Dropped ${results.size} track(s)")
            val totalClips = results.sumOf { it.droppedClipCount }
            if (totalClips > 0) append(" with $totalClips clip(s)")
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "remove ${results.size} track(s)",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                results = results,
                forced = input.force,
                snapshotId = snapshotId.value,
            ),
        )
    }

    private fun trackKind(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }
}
