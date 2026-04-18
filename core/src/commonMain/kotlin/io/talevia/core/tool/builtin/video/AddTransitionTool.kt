package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Insert a transition between two adjacent clips on the same track. The transition
 * is recorded as a thin Effect-track clip whose timeRange spans the overlap region;
 * the engines render it during export.
 *
 * Like [ApplyFilterTool], the data model lands first; engine rendering of
 * non-cut transitions follows once the basic timeline is stable on every platform.
 */
@OptIn(ExperimentalUuidApi::class)
class AddTransitionTool(
    private val store: ProjectStore,
) : Tool<AddTransitionTool.Input, AddTransitionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val fromClipId: String,
        val toClipId: String,
        val transitionName: String = "fade",
        val durationSeconds: Double = 0.5,
    )
    @Serializable data class Output(val transitionClipId: String, val trackId: String)

    override val id = "add_transition"
    override val helpText = "Add a named transition (fade, dissolve, slide, ...) between two adjacent clips."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("fromClipId") { put("type", "string") }
            putJsonObject("toClipId") { put("type", "string") }
            putJsonObject("transitionName") { put("type", "string"); put("description", "fade | dissolve | slide | wipe (engine-specific)") }
            putJsonObject("durationSeconds") { put("type", "number"); put("description", "Default 0.5s; longer transitions overlap more material.") }
        }
        put("required", JsonArray(listOf(
            JsonPrimitive("projectId"), JsonPrimitive("fromClipId"), JsonPrimitive("toClipId"),
        )))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val transitionId = ClipId(Uuid.random().toString())
        var resolvedTrackId: TrackId? = null

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val from = project.timeline.tracks.flatMap { it.clips }.firstOrNull { it.id.value == input.fromClipId }
                ?: error("fromClipId ${input.fromClipId} not found")
            val to = project.timeline.tracks.flatMap { it.clips }.firstOrNull { it.id.value == input.toClipId }
                ?: error("toClipId ${input.toClipId} not found")
            if (from.timeRange.end != to.timeRange.start) {
                error("transition only supported between adjacent clips (from ends ${from.timeRange.end}, to starts ${to.timeRange.start})")
            }
            val duration = input.durationSeconds.seconds
            val midpoint = from.timeRange.end - duration / 2
            val transitionRange = TimeRange(midpoint, duration)

            val (effectTrack, others) = pickEffectTrack(project.timeline.tracks)
            val transitionClip = Clip.Video(
                // Use a synthetic Video clip on the Effect track to carry the transition spec via filters[0].
                id = transitionId,
                timeRange = transitionRange,
                sourceRange = TimeRange(Duration.ZERO, duration),
                assetId = io.talevia.core.AssetId("transition:${input.transitionName}"),
                filters = listOf(Filter(input.transitionName, mapOf("durationSeconds" to input.durationSeconds.toFloat()))),
            )
            val newClips = (effectTrack.clips + transitionClip).sortedBy { it.timeRange.start }
            val newTrack = (effectTrack as Track.Effect).copy(clips = newClips)
            resolvedTrackId = newTrack.id
            project.copy(timeline = project.timeline.copy(tracks = others + newTrack))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "transition ${input.transitionName}",
            outputForLlm = "Added ${input.transitionName} transition between ${input.fromClipId} and ${input.toClipId}. Timeline snapshot: ${snapshotId.value}",
            data = Output(transitionId.value, resolvedTrackId!!.value),
        )
    }

    private fun pickEffectTrack(tracks: List<Track>): Pair<Track, List<Track>> {
        val match = tracks.firstOrNull { it is Track.Effect }
        return if (match != null) match to tracks.filter { it.id != match.id }
        else Track.Effect(TrackId(Uuid.random().toString())) to tracks
    }
}
