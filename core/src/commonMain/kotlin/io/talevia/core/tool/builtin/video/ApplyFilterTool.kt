package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
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
 * Attach a named filter to one or many video clips in a single atomic
 * edit. Unifies the former single-clip and batch tools
 * (`apply_filter` + `apply_filter_to_clips`) behind one entry point:
 * single-clip callers pass `clipIds=["c1"]`; batch callers use an
 * explicit list, a `trackId`, or `allVideoClips=true`.
 *
 * Selection (exactly one must be set):
 *   - `clipIds`: explicit list (e.g. from `project_query(select=clips_for_source)`
 *     or a timeline multi-select).
 *   - `trackId`: every clip on the given track.
 *   - `allVideoClips=true`: every video clip in the project.
 *
 * Non-video clips are ignored silently — text / audio don't accept
 * visual filters. Clip ids listed in `clipIds` that resolve to a
 * non-video clip or don't exist at all surface in `skipped` with a
 * reason so the agent can report partial success honestly.
 *
 * One `Part.TimelineSnapshot` per call — `revert_timeline` walks back
 * the whole batch atomically.
 *
 * Engine support: the FFmpeg engine honours all listed filters at
 * render time. Media3 (Android) bakes `brightness` / `saturation` /
 * `blur` / `vignette` / `lut`; AVFoundation (iOS) bakes
 * `brightness` / `saturation` / `blur` / `vignette` / `lut` via
 * CIFilter chains. LUT rendering on both native engines shares the
 * `.cube` parser in `core.platform.lut`.
 */
class ApplyFilterTool(
    private val store: ProjectStore,
) : Tool<ApplyFilterTool.Input, ApplyFilterTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val filterName: String,
        val params: Map<String, Float> = emptyMap(),
        /**
         * Explicit clip ids to target. Single-clip callers pass a 1-element list.
         * Mutually exclusive with `trackId` / `allVideoClips`.
         */
        val clipIds: List<String> = emptyList(),
        /** Target every clip on this track id. Mutually exclusive with `clipIds` / `allVideoClips`. */
        val trackId: String? = null,
        /** Target every video clip in the project. Mutually exclusive with `clipIds` / `trackId`. */
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

    override val id: String = "apply_filter"
    override val helpText: String =
        "Append a named filter (brightness, saturation, blur, vignette, lut, …) to one or many " +
            "video clips in one atomic edit. Selection: pass `clipIds` for an explicit list " +
            "(single-clip is a 1-element list), `trackId` to target every clip on one track, or " +
            "`allVideoClips=true` for the whole project — exactly one selector must be set. " +
            "Non-video clips (text/audio) are ignored; unresolvable clipIds are reported in " +
            "`skipped`. Emits one timeline snapshot per batch so revert_timeline rolls the whole " +
            "operation back in one step."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

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
            putJsonObject("filterName") {
                put("type", "string")
                put(
                    "description",
                    "Engine-specific filter name (brightness, saturation, blur, vignette, lut, …).",
                )
            }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Numeric parameters (e.g. {\"intensity\": 0.5}).")
                putJsonObject("additionalProperties") { put("type", "number") }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put(
                    "description",
                    "Explicit clip ids. Single-clip is a 1-element list. " +
                        "Mutually exclusive with trackId / allVideoClips.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Target every video clip on this track id. Mutually exclusive with clipIds / allVideoClips.",
                )
            }
            putJsonObject("allVideoClips") {
                put("type", "boolean")
                put(
                    "description",
                    "Target every video clip in the project. Mutually exclusive with clipIds / trackId.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("filterName"))))
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

        val pid = ctx.resolveProjectId(input.projectId)
        val appliedClipIds = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()

        val updated = store.mutate(pid) { project ->
            val requestedIds = input.clipIds.toSet()
            if (requestedIds.isNotEmpty()) {
                val allClipsByKind = project.timeline.tracks.flatMap { track ->
                    track.clips.map { it.id.value to (it is Clip.Video) }
                }.toMap()
                for (id in requestedIds) {
                    when (allClipsByKind[id]) {
                        null -> skipped += Skipped(id, "clip not found")
                        false -> skipped += Skipped(id, "clip is not a video clip (apply_filter skips text/audio)")
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
                        !input.trackId.isNullOrBlank() -> true
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
            return ToolResult(
                title = "apply ${input.filterName} (no match)",
                outputForLlm = "No video clips matched the selector — nothing to apply.",
                data = Output(
                    projectId = pid.value,
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
                projectId = pid.value,
                filterName = input.filterName,
                appliedCount = appliedClipIds.size,
                appliedClipIds = appliedClipIds,
                skipped = skipped,
            ),
        )
    }
}
