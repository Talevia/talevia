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
 * Delete one or many transitions by id in a single atomic edit — the list-form
 * companion to [AddTransitionTool]. `add_transition` records each transition as
 * a synthetic [Clip.Video] on an [Track.Effect] track whose [Clip.Video.assetId]
 * is the sentinel `"transition:<name>"`; [Clip.Video.filters] carries the
 * engine-side transition name + duration.
 *
 * Atomic semantics: every listed id must resolve to a real transition clip on
 * an effect track. If any id is missing, points to a non-effect-track clip, or
 * points to a non-transition effect clip, the whole batch aborts and
 * `talevia.json` is left untouched. The strict-validation-or-nothing contract
 * keeps the agent honest about what it's deleting — silently skipping unknown
 * ids would mask typos.
 *
 * No ripple — transitions live in the overlap window between two adjacent
 * video clips, so deleting them leaves the timeline's overall timing unchanged.
 * The two underlying clips remain adjacent; the engine falls back to a hard cut
 * at render time.
 *
 * One `Part.TimelineSnapshot` per call. `revert_timeline` walks back the whole
 * batch atomically.
 */
class RemoveTransitionTool(
    private val store: ProjectStore,
) : Tool<RemoveTransitionTool.Input, RemoveTransitionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** Transition clip ids (as returned by `add_transition`). At least one required. */
        val transitionClipIds: List<String>,
    )

    @Serializable data class ItemResult(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        /** Count of transitions still on the affected effect track(s) after removal. */
        val remainingTransitionsTotal: Int,
        val snapshotId: String,
    )

    override val id: String = "remove_transitions"
    override val helpText: String =
        "Delete one or many transitions atomically by id (the ids returned by add_transitions). " +
            "Strictly scoped to transitions (effect-track clips with the `transition:<name>` " +
            "sentinel assetId) — refuses regular video / audio / text clips so you can't remove " +
            "the wrong thing by id confusion. Does not ripple other clips; flanking video clips " +
            "stay put and render as a hard cut where the transition was. All-or-nothing: if any " +
            "listed id is missing or not a transition, nothing is deleted. Emits one timeline " +
            "snapshot per call."
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
            putJsonObject("transitionClipIds") {
                put("type", "array")
                put("description", "Transition clip ids (from add_transitions). At least one.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("transitionClipIds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.transitionClipIds.isNotEmpty()) { "transitionClipIds must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()
        var remainingTotal = 0

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            val touchedTrackIds = mutableSetOf<String>()
            input.transitionClipIds.forEachIndexed { idx, transitionClipId ->
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
                results += ItemResult(
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
                results = results,
                remainingTransitionsTotal = remainingTotal,
                snapshotId = snapshotId.value,
            ),
        )
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
