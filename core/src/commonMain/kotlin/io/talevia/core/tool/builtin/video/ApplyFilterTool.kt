package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
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

/**
 * Attach a named filter to an existing video clip. The filter list is appended;
 * remove and replace are out of scope for v0 (a future tool can take an index).
 *
 * Note: the FFmpeg engine honours all the filters below at render time.
 * The Media3 engine now bakes `brightness` / `saturation` / `blur`; `vignette`
 * and `lut` still fall through as no-ops there (Media3 has no built-in
 * Vignette and needs a `.cube` parser for LUT). AVFoundation has none yet.
 */
class ApplyFilterTool(
    private val store: ProjectStore,
) : Tool<ApplyFilterTool.Input, ApplyFilterTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val filterName: String,
        val params: Map<String, Float> = emptyMap(),
    )
    @Serializable data class Output(val clipId: String, val filterCount: Int)

    override val id = "apply_filter"
    override val helpText = "Append a named filter (e.g. blur, brightness, saturation) to a video clip with optional float params."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("filterName") { put("type", "string"); put("description", "Engine-specific filter name (blur, brightness, saturation, vignette, ...)") }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Numeric parameters (e.g. {\"intensity\": 0.5}).")
                putJsonObject("additionalProperties") { put("type", "number") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"), JsonPrimitive("filterName"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var filterCount = 0
        val project = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
                if (target !is Clip.Video) error("apply_filter only supports video clips")
                val updated = target.copy(filters = target.filters + Filter(input.filterName, input.params))
                filterCount = updated.filters.size
                replaceClip(track, target, updated)
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        if (filterCount == 0) error("clip ${input.clipId} not found in project ${input.projectId}")
        val snapshotId = emitTimelineSnapshot(ctx, project.timeline)
        return ToolResult(
            title = "filter ${input.filterName}",
            outputForLlm = "Applied filter '${input.filterName}' to clip ${input.clipId} (now $filterCount filters total). Timeline snapshot: ${snapshotId.value}",
            data = Output(input.clipId, filterCount),
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
