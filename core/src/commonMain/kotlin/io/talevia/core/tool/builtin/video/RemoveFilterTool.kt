package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
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

/**
 * Remove a named filter from one or many video clips atomically — the list-form
 * counterpart to [ApplyFilterTool]. Until a list form existed, an agent that
 * stacked "blur" on four clips had to call `remove_filter` four times, burning
 * four turns + four snapshots to undo what `apply_filter` did in one.
 *
 * Broadcast shape: single shared `filterName` applied to `clipIds`. All filters
 * whose `name` matches are dropped from each clip. Idempotent per clip — a
 * clip without the named filter contributes `removedCount=0` to its item
 * result rather than aborting the batch; speculative cleanup shouldn't be
 * punished.
 *
 * Scope choices matching the rest of the video toolkit:
 * - Video clips only (filters only live on `Clip.Video`). A clipId pointing
 *   at a non-video clip aborts the batch.
 * - All-or-nothing on resolution: an unresolvable clipId aborts; the "filter
 *   isn't there" case is accepted silently per clip.
 * - One `Part.TimelineSnapshot` per call.
 */
class RemoveFilterTool(
    private val store: ProjectStore,
) : Tool<RemoveFilterTool.Input, RemoveFilterTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipIds: List<String>,
        /** Remove all filters with this name (e.g. "blur", "vignette"). */
        val filterName: String,
    )

    @Serializable data class ItemResult(
        val clipId: String,
        val removedCount: Int,
        val remainingFilterCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val filterName: String,
        val results: List<ItemResult>,
        val totalRemoved: Int,
        val snapshotId: String,
    )

    override val id: String = "remove_filter"
    override val helpText: String =
        "Remove all filters with the given name from one or many video clips atomically. " +
            "Idempotent per clip — removing a filter that isn't present on a given clip returns " +
            "removedCount=0 for that item (doesn't abort). Unresolvable clipIds or non-video " +
            "clips do abort the whole batch."
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
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Video clip ids to remove the filter from. At least one.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("filterName") {
                put("type", "string")
                put(
                    "description",
                    "Filter name to remove (e.g. blur, brightness, vignette). Matching filters " +
                        "are removed from every clip.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("clipIds"), JsonPrimitive("filterName"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty" }
        require(input.filterName.isNotBlank()) { "filterName must not be blank" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.clipIds.forEachIndexed { idx, clipId ->
                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == clipId }?.let { track to it }
                } ?: error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
                val (track, clip) = hit
                val video = clip as? Clip.Video ?: error(
                    "clipIds[$idx] ($clipId): remove_filter only supports video clips " +
                        "(got ${clip::class.simpleName}).",
                )
                val kept = video.filters.filter { it.name != input.filterName }
                val removed = video.filters.size - kept.size
                val updatedClip = video.copy(filters = kept)
                val newTrack = replaceClipOnTrack(track, video, updatedClip)
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += ItemResult(
                    clipId = clipId,
                    removedCount = removed,
                    remainingFilterCount = kept.size,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val total = results.sumOf { it.removedCount }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "remove ${input.filterName} × ${results.size}",
            outputForLlm = "Removed $total filter(s) named '${input.filterName}' across ${results.size} " +
                "clip(s). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                filterName = input.filterName,
                results = results,
                totalRemoved = total,
                snapshotId = snapshotId.value,
            ),
        )
    }

    private fun replaceClipOnTrack(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
