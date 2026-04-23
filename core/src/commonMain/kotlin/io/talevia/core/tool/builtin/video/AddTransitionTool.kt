package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Insert one or many transitions atomically between adjacent clip pairs. Each
 * transition is recorded as a thin Effect-track clip whose timeRange spans the
 * overlap region; engines render it during export.
 *
 * Per-item shape — every transition has its own `fromClipId / toClipId /
 * transitionName / durationSeconds`, because "add a 0.5s fade here and a 1s
 * dissolve there" is the natural batch request. Each pair must be on the same
 * track and already adjacent (from.end == to.start). Failures mid-batch abort
 * the whole call and leave `talevia.json` untouched.
 *
 * Like [ApplyFilterTool], the data model lands first; engine rendering of
 * non-cut transitions follows once the basic timeline is stable on every platform.
 */
@OptIn(ExperimentalUuidApi::class)
class AddTransitionTool(
    private val store: ProjectStore,
) : Tool<AddTransitionTool.Input, AddTransitionTool.Output> {

    @Serializable data class Item(
        val fromClipId: String,
        val toClipId: String,
        val transitionName: String = "fade",
        val durationSeconds: Double = 0.5,
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
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val fromClipId: String,
        val toClipId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "add_transitions"
    override val helpText = "Add one or many transitions atomically between adjacent clip pairs. " +
        "Each item specifies fromClipId, toClipId, optional transitionName (default 'fade') and " +
        "durationSeconds (default 0.5). Each pair must be on the same track and already adjacent. " +
        "All-or-nothing: if any item fails validation, nothing is inserted. One timeline snapshot " +
        "per call."
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
                put("description", "Transitions to insert. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("fromClipId") { put("type", "string") }
                        putJsonObject("toClipId") { put("type", "string") }
                        putJsonObject("transitionName") {
                            put("type", "string")
                            put("description", "fade | dissolve | slide | wipe (engine-specific)")
                        }
                        putJsonObject("durationSeconds") {
                            put("type", "number")
                            put("description", "Default 0.5s; longer transitions overlap more material.")
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("fromClipId"), JsonPrimitive("toClipId"))),
                    )
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
            require(item.durationSeconds > 0.0) {
                "items[$idx].durationSeconds must be > 0 (got ${item.durationSeconds})"
            }
        }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val from = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.fromClipId }?.let { track to it }
                } ?: error("items[$idx]: fromClipId ${item.fromClipId} not found")
                val to = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.toClipId }?.let { track to it }
                } ?: error("items[$idx]: toClipId ${item.toClipId} not found")
                val (fromTrack, fromClip) = from
                val (toTrack, toClip) = to
                if (fromTrack.id != toTrack.id) {
                    error("items[$idx]: transition only supported between clips on the same track")
                }
                if (fromClip !is Clip.Video || toClip !is Clip.Video) {
                    error("items[$idx]: transition only supports video clips")
                }
                if (fromClip.timeRange.end != toClip.timeRange.start) {
                    error(
                        "items[$idx]: transition only supported between adjacent clips " +
                            "(from ends ${fromClip.timeRange.end}, to starts ${toClip.timeRange.start})",
                    )
                }
                val duration = item.durationSeconds.seconds
                val midpoint = fromClip.timeRange.end - duration / 2
                val transitionRange = TimeRange(midpoint, duration)

                val transitionId = ClipId(Uuid.random().toString())
                val effectTrack = pickEffectTrack(tracks)
                val transitionClip = Clip.Video(
                    id = transitionId,
                    timeRange = transitionRange,
                    sourceRange = TimeRange(Duration.ZERO, duration),
                    assetId = io.talevia.core.AssetId("transition:${item.transitionName}"),
                    filters = listOf(
                        Filter(item.transitionName, mapOf("durationSeconds" to item.durationSeconds.toFloat())),
                    ),
                )
                val newClips = (effectTrack.clips + transitionClip).sortedBy { it.timeRange.start }
                val newTrack = effectTrack.copy(clips = newClips)
                tracks = upsertTrackPreservingOrder(tracks, newTrack)

                results += ItemResult(
                    transitionClipId = transitionId.value,
                    trackId = newTrack.id.value,
                    transitionName = item.transitionName,
                    fromClipId = item.fromClipId,
                    toClipId = item.toClipId,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "add ${results.size} transition(s)",
            outputForLlm = buildString {
                append("Added ${results.size} transition(s): ")
                append(results.joinToString(", ") { "${it.transitionName} between ${it.fromClipId}→${it.toClipId}" })
                append(". Timeline snapshot: ${snapshotId.value}")
            },
            data = Output(pid.value, results, snapshotId.value),
        )
    }

    private fun pickEffectTrack(tracks: List<Track>): Track.Effect {
        val match = tracks.firstOrNull { it is Track.Effect }
        return match as? Track.Effect ?: Track.Effect(TrackId(Uuid.random().toString()))
    }
}
