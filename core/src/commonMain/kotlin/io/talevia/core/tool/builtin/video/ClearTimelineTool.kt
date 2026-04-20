package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
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
 * Bulk-clear every clip from the project's timeline in one call. The
 * clip-by-clip path (`remove_clip`) is fine for surgical edits but useless
 * when the agent wants to reset and rebuild the timeline from scratch —
 * e.g. "scrap this and try a different cut", "the source bible changed,
 * re-generate every clip", or a mid-session pivot to a new storyboard. The
 * alternative today is N calls to `remove_clip` or a `revert_timeline` to
 * a pre-edit snapshot (which requires one to exist).
 *
 * Preserves everything outside the timeline: assets, source DAG, lockfile,
 * render cache, snapshots, output profile. Only `Timeline.tracks` clip
 * lists and `Timeline.duration` change.
 *
 * `preserveTracks` (default true) keeps the existing track skeleton so
 * subsequent `add_clip` calls land on the same track ids the agent may
 * have already referenced earlier in the conversation. Pass `false` to
 * drop the tracks entirely — useful when the new layout needs a fresh
 * track structure and the old ids are clutter.
 *
 * Emits a `Part.TimelineSnapshot` so `revert_timeline` can restore the
 * pre-clear timeline in one step.
 */
class ClearTimelineTool(
    private val store: ProjectStore,
) : Tool<ClearTimelineTool.Input, ClearTimelineTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val preserveTracks: Boolean = true,
    )

    @Serializable data class Output(
        val projectId: String,
        val removedClipCount: Int,
        val removedTrackCount: Int,
        val remainingTrackCount: Int,
    )

    override val id: String = "clear_timeline"
    override val helpText: String =
        "Remove every clip from the timeline. Preserves assets, source DAG, lockfile, render " +
            "cache, snapshots, and output profile. Pass preserveTracks=false to also drop the " +
            "track skeleton. Emits a timeline snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("preserveTracks") {
                put("type", "boolean")
                put(
                    "description",
                    "Keep the track skeleton (default true) so existing track ids are still valid; " +
                        "pass false to drop tracks entirely.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var removedClips = 0
        var removedTracks = 0
        var remainingTracks = 0
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val oldTracks = project.timeline.tracks
            removedClips = oldTracks.sumOf { it.clips.size }
            val newTracks = if (input.preserveTracks) {
                oldTracks.map { track ->
                    when (track) {
                        is Track.Video -> track.copy(clips = emptyList())
                        is Track.Audio -> track.copy(clips = emptyList())
                        is Track.Subtitle -> track.copy(clips = emptyList())
                        is Track.Effect -> track.copy(clips = emptyList())
                    }
                }
            } else {
                emptyList()
            }
            removedTracks = oldTracks.size - newTracks.size
            remainingTracks = newTracks.size
            project.copy(
                timeline = project.timeline.copy(
                    tracks = newTracks,
                    duration = Duration.ZERO,
                ),
            )
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "clear timeline ${input.projectId}",
            outputForLlm = "Cleared timeline: removed $removedClips clip(s)" +
                (if (!input.preserveTracks) " and $removedTracks track(s)" else "") +
                ". $remainingTracks track(s) remain. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                projectId = input.projectId,
                removedClipCount = removedClips,
                removedTrackCount = removedTracks,
                remainingTrackCount = remainingTracks,
            ),
        )
    }
}
