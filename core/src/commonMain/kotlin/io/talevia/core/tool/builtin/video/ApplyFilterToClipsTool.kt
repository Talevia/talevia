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
 * Batch variant of `apply_filter` — stamps the same (filter, params)
 * onto N video clips in a single `ProjectStore.mutate`, emits one
 * `TimelineSnapshot`. Targets the "vignette every shot in scene 2" and
 * "drop the same LUT-prep brightness onto every clip" workflows that
 * would otherwise need N chat round-trips.
 *
 * Selection shapes (mutually exclusive — exactly one must be provided):
 *   - `clipIds`: explicit list (e.g. from `list_clips_for_source` or a
 *     timeline multi-select)
 *   - `trackId`: every video clip on the given track
 *   - `allVideoClips=true`: every video clip in the project
 *
 * Non-video clips are ignored silently — text / audio don't accept
 * visual filters. Clip ids listed in `clipIds` that resolve to a
 * non-video clip or don't exist at all surface in `skipped` with a
 * reason so the agent can report partial success honestly.
 *
 * Permission: `"timeline.write"` — same bucket as `apply_filter`.
 */
class ApplyFilterToClipsTool(
    private val store: ProjectStore,
) : Tool<ApplyFilterToClipsTool.Input, ApplyFilterToClipsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val filterName: String,
        val params: Map<String, Float> = emptyMap(),
        val clipIds: List<String> = emptyList(),
        val trackId: String? = null,
        val allVideoClips: Boolean = false,
    )

    @Serializable data class Skipped(
        val clipId: String,
        val reason: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val filterName: String,
        val appliedCount: Int,
        val appliedClipIds: List<String>,
        val skipped: List<Skipped>,
    )

    override val id: String = "apply_filter_to_clips"
    override val helpText: String =
        "Apply the same filter to many video clips in one atomic edit. Selection: pass " +
            "clipIds for an explicit list, trackId to target every clip on one track, or " +
            "allVideoClips=true for the whole project. Exactly one selector must be set. " +
            "Non-video clips (text/audio) are ignored; unresolvable clipIds are reported " +
            "in `skipped`. One timeline snapshot per batch — revert_timeline walks back " +
            "the whole batch in one step."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("filterName") {
                put("type", "string")
                put("description", "brightness | saturation | blur | vignette | … — same names as apply_filter.")
            }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Numeric parameters (e.g. {\"intensity\": 0.5}).")
                putJsonObject("additionalProperties") { put("type", "number") }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Explicit clip ids to target. Mutually exclusive with trackId / allVideoClips.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("trackId") {
                put("type", "string")
                put("description", "Target every video clip on this track id. Mutually exclusive with clipIds / allVideoClips.")
            }
            putJsonObject("allVideoClips") {
                put("type", "boolean")
                put("description", "Target every video clip in the project. Mutually exclusive with clipIds / trackId.")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("filterName"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val selectorCount = listOf(
            input.clipIds.isNotEmpty(),
            !input.trackId.isNullOrBlank(),
            input.allVideoClips,
        ).count { it }
        require(selectorCount == 1) {
            "exactly one of clipIds / trackId / allVideoClips must be provided (got $selectorCount)"
        }

        val pid = ProjectId(input.projectId)
        val appliedClipIds = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()

        val updated = store.mutate(pid) { project ->
            val requestedIds = input.clipIds.toSet()
            // First pass: flag unresolvable ids (so the agent sees partial-failure signals
            // instead of "0 applied" with no reason).
            if (requestedIds.isNotEmpty()) {
                val allClipIdsByKind = project.timeline.tracks.flatMap { track ->
                    track.clips.map { it.id.value to (it is Clip.Video) }
                }.toMap()
                for (id in requestedIds) {
                    val isVideo = allClipIdsByKind[id]
                    when (isVideo) {
                        null -> skipped += Skipped(id, "clip not found")
                        false -> skipped += Skipped(id, "clip is not a video clip (apply_filter_to_clips skips text/audio)")
                        true -> Unit
                    }
                }
            }

            val newTracks = project.timeline.tracks.map { track ->
                val shouldVisit = when {
                    input.clipIds.isNotEmpty() -> true
                    !input.trackId.isNullOrBlank() -> track.id.value == input.trackId
                    input.allVideoClips -> track is Track.Video
                    else -> false
                }
                if (!shouldVisit) return@map track
                val newClips = track.clips.map { c ->
                    val matches = when {
                        input.clipIds.isNotEmpty() -> c.id.value in requestedIds
                        !input.trackId.isNullOrBlank() -> true // whole track
                        input.allVideoClips -> true
                        else -> false
                    }
                    if (!matches || c !is Clip.Video) {
                        c
                    } else {
                        appliedClipIds += c.id.value
                        c.copy(filters = c.filters + Filter(input.filterName, input.params))
                    }
                }
                when (track) {
                    is Track.Video -> track.copy(clips = newClips)
                    is Track.Audio -> track.copy(clips = newClips)
                    is Track.Subtitle -> track.copy(clips = newClips)
                    is Track.Effect -> track.copy(clips = newClips)
                }
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }

        if (appliedClipIds.isEmpty() && skipped.isEmpty()) {
            // trackId or allVideoClips but nothing matched — soft no-op.
            return ToolResult(
                title = "apply ${input.filterName} (no match)",
                outputForLlm = "No video clips matched the selector — nothing to apply.",
                data = Output(
                    projectId = input.projectId,
                    filterName = input.filterName,
                    appliedCount = 0,
                    appliedClipIds = emptyList(),
                    skipped = emptyList(),
                ),
            )
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Applied ${input.filterName} to ${appliedClipIds.size} clip(s)")
            if (skipped.isNotEmpty()) append("; skipped ${skipped.size}")
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "apply ${input.filterName} × ${appliedClipIds.size}",
            outputForLlm = summary,
            data = Output(
                projectId = input.projectId,
                filterName = input.filterName,
                appliedCount = appliedClipIds.size,
                appliedClipIds = appliedClipIds,
                skipped = skipped,
            ),
        )
    }
}
