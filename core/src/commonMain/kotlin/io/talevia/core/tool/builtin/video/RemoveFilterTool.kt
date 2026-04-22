package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
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
 * The missing counterpart to [ApplyFilterTool]. Until now an agent that
 * added a filter and wanted to back it out had exactly one option —
 * `revert_timeline` — which blows away *every* subsequent edit to land
 * one filter undo. That makes iterative "try a filter, hate it, try
 * another" workflows impossibly expensive.
 *
 * This tool removes all filters on a clip whose `name` matches
 * [Input.filterName]. Duplicates are rare but legal; if the agent
 * stacked `blur` twice both go. If the name isn't present we return a
 * successful no-op (removedCount = 0) rather than erroring — agents
 * that speculatively clean up without checking first shouldn't be
 * punished for it.
 *
 * Scope choices matching the rest of the video toolkit:
 * - Video clips only (filters only live on `Clip.Video`).
 * - Clip lookup is cross-track (we don't require the caller to know
 *   which track holds the clip), same as every other per-clip tool.
 * - Emits a timeline snapshot for `revert_timeline`.
 *
 * Clearing *all* filters in one call is out of scope — callers can
 * `list_timeline_clips` + iterate, or a dedicated `clear_filters` can
 * land later if it proves common. Keeping this tool keyed on name
 * matches how filters are created (`apply_filter` takes a name), so
 * the symmetry is cleanest.
 */
class RemoveFilterTool(
    private val store: ProjectStore,
) : Tool<RemoveFilterTool.Input, RemoveFilterTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** Remove all filters with this name (e.g. "blur", "vignette"). */
        val filterName: String,
    )

    @Serializable data class Output(
        val clipId: String,
        val removedCount: Int,
        val remainingFilterCount: Int,
    )

    override val id: String = "remove_filter"
    override val helpText: String =
        "Remove all filters with the given name from a video clip. " +
            "Idempotent — removing a filter that isn't present returns removedCount=0."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("filterName") {
                put("type", "string")
                put("description", "Filter name to remove (e.g. blur, brightness, vignette). All filters matching this name are removed.")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("clipId"),
                    JsonPrimitive("filterName"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var found = false
        var removed = 0
        var remaining = 0

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
                found = true
                if (target !is Clip.Video) error("remove_filter only supports video clips")
                val kept = target.filters.filter { it.name != input.filterName }
                removed = target.filters.size - kept.size
                remaining = kept.size
                val updatedClip = target.copy(filters = kept)
                replaceClip(track, target, updatedClip)
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        if (!found) error("clip ${input.clipId} not found in project ${input.projectId}")

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "remove filter ${input.filterName}",
            outputForLlm = "Removed $removed filter(s) named '${input.filterName}' from clip ${input.clipId} " +
                "($remaining remaining). Timeline snapshot: ${snapshotId.value}",
            data = Output(input.clipId, removed, remaining),
        )
    }

    private fun replaceClip(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
