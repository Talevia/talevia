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
 * Insert or remove transitions between adjacent clip pairs — the consolidated
 * action-dispatched form that replaces the previous `add_transitions` +
 * `remove_transitions` pair (first step of
 * `debt-consolidate-video-add-remove-verbs`, 2026-04-23).
 *
 * Action-dispatched because transitions only have two meaningful verbs and
 * they both already take batch input — the Add path takes a list of
 * `fromClipId/toClipId/transitionName/durationSeconds` quads and the Remove
 * path takes a list of transition clip ids. Consolidating them into one
 * tool cuts one top-level LLM tool-spec entry (≈ 300 tokens per turn)
 * without losing any behavioural surface.
 *
 * Atomic semantics are preserved per action: any mid-batch validation
 * failure aborts the whole call and leaves `talevia.json` untouched.
 * Each call emits exactly one `Part.TimelineSnapshot` so
 * `revert_timeline` walks back the whole batch in one step.
 */
@OptIn(ExperimentalUuidApi::class)
class TransitionActionTool(
    private val store: ProjectStore,
) : Tool<TransitionActionTool.Input, TransitionActionTool.Output> {

    /** One add-transition request. */
    @Serializable data class AddItem(
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
        /** `"add"` or `"remove"`. Case-sensitive. */
        val action: String,
        /** Required when `action="add"`. Transitions to insert. */
        val items: List<AddItem>? = null,
        /** Required when `action="remove"`. Transition clip ids (from prior `add`). */
        val transitionClipIds: List<String>? = null,
    )

    @Serializable data class AddResult(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val fromClipId: String,
        val toClipId: String,
    )

    @Serializable data class RemoveResult(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val snapshotId: String,
        /** Populated when `action="add"`. */
        val added: List<AddResult> = emptyList(),
        /** Populated when `action="remove"`. */
        val removed: List<RemoveResult> = emptyList(),
        /** Populated when `action="remove"` — transitions still on the affected track(s). */
        val remainingTransitionsTotal: Int = 0,
    )

    override val id: String = "transition_action"
    override val helpText: String =
        "Insert or remove transitions between adjacent clip pairs atomically. " +
            "action=add + items (each: fromClipId, toClipId, transitionName default 'fade', " +
            "durationSeconds default 0.5; pairs must be on same track + adjacent). " +
            "action=remove + transitionClipIds (ids from a prior add); scoped to effect-track " +
            "clips with the `transition:` sentinel assetId. All-or-nothing; one snapshot per call."
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
            putJsonObject("action") {
                put("type", "string")
                put("description", "`add` to insert transitions, `remove` to delete them.")
                put("enum", JsonArray(listOf(JsonPrimitive("add"), JsonPrimitive("remove"))))
            }
            putJsonObject("items") {
                put("type", "array")
                put("description", "Required when action=add. Transitions to insert; at least one.")
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
            putJsonObject("transitionClipIds") {
                put("type", "array")
                put(
                    "description",
                    "Required when action=remove. Transition clip ids (from prior add_transitions). At least one.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "add" -> executeAdd(pid, input, ctx)
            "remove" -> executeRemove(pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove",
            )
        }
    }

    private suspend fun executeAdd(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.items
            ?: error("action=add requires `items` (omit `transitionClipIds`)")
        require(items.isNotEmpty()) { "items must not be empty" }
        items.forEachIndexed { idx, item ->
            require(item.durationSeconds > 0.0) {
                "items[$idx].durationSeconds must be > 0 (got ${item.durationSeconds})"
            }
        }
        val results = mutableListOf<AddResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
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
                    assetId = io.talevia.core.AssetId("$TRANSITION_ASSET_PREFIX${item.transitionName}"),
                    filters = listOf(
                        Filter(item.transitionName, mapOf("durationSeconds" to item.durationSeconds.toFloat())),
                    ),
                )
                val newClips = (effectTrack.clips + transitionClip).sortedBy { it.timeRange.start }
                val newTrack = effectTrack.copy(clips = newClips)
                tracks = upsertTrackPreservingOrder(tracks, newTrack)

                results += AddResult(
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
            data = Output(
                projectId = pid.value,
                action = "add",
                snapshotId = snapshotId.value,
                added = results,
            ),
        )
    }

    private suspend fun executeRemove(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val ids = input.transitionClipIds
            ?: error("action=remove requires `transitionClipIds` (omit `items`)")
        require(ids.isNotEmpty()) { "transitionClipIds must not be empty" }
        val results = mutableListOf<RemoveResult>()
        var remainingTotal = 0

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            val touchedTrackIds = mutableSetOf<String>()
            ids.forEachIndexed { idx, transitionClipId ->
                val locatedTrack = tracks.firstOrNull { track ->
                    track is Track.Effect && track.clips.any { it.id.value == transitionClipId }
                }
                val locatedClip = locatedTrack?.clips?.firstOrNull { it.id.value == transitionClipId }

                if (locatedTrack == null || locatedClip == null) {
                    val elsewhere = tracks.firstOrNull { track ->
                        track !is Track.Effect && track.clips.any { it.id.value == transitionClipId }
                    }
                    if (elsewhere != null) {
                        error(
                            "transitionClipIds[$idx] ($transitionClipId) is on a ${trackKindOf(elsewhere)} " +
                                "track, not a transition. Use remove_clips for regular clips.",
                        )
                    }
                    error("transitionClipIds[$idx] ($transitionClipId) not found in project ${pid.value}")
                }
                if (locatedClip !is Clip.Video || !locatedClip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)) {
                    error(
                        "transitionClipIds[$idx] ($transitionClipId) is on the effect track but is not " +
                            "a transition (assetId '${(locatedClip as? Clip.Video)?.assetId?.value ?: "n/a"}' " +
                            "does not start with '$TRANSITION_ASSET_PREFIX'). Use remove_clips if you meant " +
                            "a non-transition effect clip.",
                    )
                }

                val transitionName = locatedClip.filters.firstOrNull()?.name
                    ?: locatedClip.assetId.value.removePrefix(TRANSITION_ASSET_PREFIX)
                results += RemoveResult(
                    transitionClipId = transitionClipId,
                    trackId = locatedTrack.id.value,
                    transitionName = transitionName,
                )
                touchedTrackIds += locatedTrack.id.value

                val keep = locatedTrack.clips.filter { it.id.value != transitionClipId }
                val newTrack = (locatedTrack as Track.Effect).copy(clips = keep)
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
            }
            remainingTotal = tracks.filter { it.id.value in touchedTrackIds }.sumOf { track ->
                track.clips.count { clip ->
                    clip is Clip.Video && clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)
                }
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Removed ${results.size} transition(s)")
            if (results.isNotEmpty()) {
                val names = results.joinToString(", ") { "${it.transitionName} (${it.transitionClipId})" }
                append(": ").append(names)
            }
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "remove ${results.size} transition(s)",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                action = "remove",
                snapshotId = snapshotId.value,
                removed = results,
                remainingTransitionsTotal = remainingTotal,
            ),
        )
    }

    private fun pickEffectTrack(tracks: List<Track>): Track.Effect {
        val match = tracks.firstOrNull { it is Track.Effect }
        return match as? Track.Effect ?: Track.Effect(TrackId(Uuid.random().toString()))
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    companion object {
        private const val TRANSITION_ASSET_PREFIX = "transition:"
    }
}
