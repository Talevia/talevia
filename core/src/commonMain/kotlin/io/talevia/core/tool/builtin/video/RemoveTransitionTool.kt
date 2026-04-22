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
 * Delete a transition by id — the missing companion to [AddTransitionTool].
 * `add_transition` records a transition as a synthetic [Clip.Video] on an
 * [Track.Effect] track whose [Clip.Video.assetId] is the sentinel
 * `"transition:<name>"`; [Clip.Video.filters] carries the engine-side
 * transition name + duration.
 *
 * `remove_clip` would technically work on that clip, but it leaks the
 * implementation detail ("transitions are video clips on the effect track")
 * into agent reasoning and accepts any clip id — silently deleting a
 * regular video clip if the agent confuses handles. This tool validates
 * that the target is a transition (effect-track, sentinel assetId) and
 * refuses to touch anything else. It's a tighter contract for an agent
 * that asked to "remove that fade".
 *
 * No ripple — transitions live in the overlap window between two adjacent
 * video clips, so deleting one leaves the timeline's overall timing
 * unchanged. The two underlying clips remain adjacent; the engine falls
 * back to a hard cut at render time.
 *
 * Emits a single `Part.TimelineSnapshot` so `revert_timeline` can
 * re-insert the transition.
 */
class RemoveTransitionTool(
    private val store: ProjectStore,
) : Tool<RemoveTransitionTool.Input, RemoveTransitionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val transitionClipId: String,
    )

    @Serializable data class Output(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val remainingTransitionsOnTrack: Int,
    )

    override val id: String = "remove_transition"
    override val helpText: String =
        "Delete a transition by id. Strictly scoped to transitions (effect-track clips with the " +
            "`transition:<name>` sentinel assetId) — refuses regular video / audio / text clips so " +
            "you can't remove the wrong thing by id confusion. Does not ripple other clips; the two " +
            "flanking video clips stay put and render as a hard cut. Emits a timeline snapshot."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("transitionClipId") {
                put("type", "string")
                put("description", "The id returned by add_transition (the synthetic effect-track clip).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("transitionClipId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var resolvedTrackId: String? = null
        var resolvedName: String? = null
        var remaining = 0

        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val tracks = project.timeline.tracks

            val locatedTrack = tracks.firstOrNull { track ->
                track is Track.Effect && track.clips.any { it.id.value == input.transitionClipId }
            }
            val locatedClip = locatedTrack?.clips?.firstOrNull { it.id.value == input.transitionClipId }

            if (locatedTrack == null || locatedClip == null) {
                // If the id matches a clip on a non-effect track, surface that specifically so the
                // agent learns they picked the wrong handle rather than a generic "not found".
                val elsewhere = tracks.firstOrNull { track ->
                    track !is Track.Effect && track.clips.any { it.id.value == input.transitionClipId }
                }
                if (elsewhere != null) {
                    error(
                        "clip ${input.transitionClipId} is on a ${trackKindOf(elsewhere)} track, not a transition. " +
                            "Use remove_clip for regular clips.",
                    )
                }
                error("transition ${input.transitionClipId} not found in project ${input.projectId}")
            }
            if (locatedClip !is Clip.Video || !locatedClip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)) {
                error(
                    "clip ${input.transitionClipId} is on the effect track but is not a transition (assetId " +
                        "'${(locatedClip as? Clip.Video)?.assetId?.value ?: "n/a"}' does not start with " +
                        "'$TRANSITION_ASSET_PREFIX'). Use remove_clip if you meant a non-transition effect clip.",
                )
            }

            resolvedTrackId = locatedTrack.id.value
            resolvedName = locatedClip.filters.firstOrNull()?.name
                ?: locatedClip.assetId.value.removePrefix(TRANSITION_ASSET_PREFIX)
            val keep = locatedTrack.clips.filter { it.id.value != input.transitionClipId }
            remaining = keep.count { clip ->
                clip is Clip.Video && clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)
            }
            val newTrack = (locatedTrack as Track.Effect).copy(clips = keep)
            project.copy(
                timeline = project.timeline.copy(
                    tracks = tracks.map { if (it.id == newTrack.id) newTrack else it },
                ),
            )
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "remove transition ${input.transitionClipId}",
            outputForLlm = "Removed ${resolvedName} transition ${input.transitionClipId} from track $resolvedTrackId " +
                "($remaining transition(s) remain on this effect track). Timeline snapshot: ${snapshotId.value}",
            data = Output(
                transitionClipId = input.transitionClipId,
                trackId = resolvedTrackId!!,
                transitionName = resolvedName!!,
                remainingTransitionsOnTrack = remaining,
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
