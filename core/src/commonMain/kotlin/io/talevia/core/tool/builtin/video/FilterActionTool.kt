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
 * Apply or remove named filters on one or many video clips — the
 * consolidated action-dispatched form that replaces
 * `ApplyFilterTool` + `RemoveFilterTool`
 * (`debt-consolidate-video-filter-lut-apply-remove` first pass,
 * 2026-04-23).
 *
 * The apply and remove branches share the same entity (video-clip
 * filters, by `filterName`) so consolidating saves one LLM tool-spec
 * entry (~300 tokens per turn) while preserving both operations
 * behaviour-exactly. `ApplyLutTool` stays separate — it carries
 * LUT-specific semantics (`styleBibleId` resolution + `sourceBinding`
 * cascade) that would muddle a unified filter shape.
 *
 * ## Input contract
 *
 * - `action` = `"apply"` or `"remove"` (required).
 * - Both: `filterName` required.
 * - `action="apply"`: pass **exactly one** selector — `clipIds`
 *   (explicit list, single-clip = 1-element list), `trackId` (every
 *   clip on one track), or `allVideoClips=true`. `params` is
 *   optional. Non-video clips silently skipped; unresolvable clipIds
 *   reported in `skipped`.
 * - `action="remove"`: `clipIds` required (no `trackId` /
 *   `allVideoClips` shortcut — mirrors the previous
 *   `remove_filter`'s semantics). Idempotent per clip. Non-video or
 *   unresolvable clipIds abort the whole batch.
 *
 * The remove path intentionally doesn't gain `trackId` /
 * `allVideoClips` shortcuts in this cycle — broadening its selector
 * semantics is a separate design decision; this consolidation is
 * behaviour-preserving only.
 *
 * ## Output
 *
 * `action="apply"` populates `appliedClipIds` + `skipped`.
 * `action="remove"` populates `removed` (per-clip count + remaining
 * count + total). Both actions emit exactly one
 * `Part.TimelineSnapshot`; `revert_timeline` walks the batch back in
 * one step.
 */
class FilterActionTool(
    private val store: ProjectStore,
) : Tool<FilterActionTool.Input, FilterActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `"apply"` or `"remove"`. Case-sensitive. */
        val action: String,
        val filterName: String,
        /** Apply-only. Numeric parameters attached to the filter (e.g. `{"intensity": 0.5}`). */
        val params: Map<String, Float> = emptyMap(),
        /**
         * Both paths. Apply: one of three selectors (mutually exclusive with
         * `trackId` / `allVideoClips`). Remove: required list of video clips.
         */
        val clipIds: List<String> = emptyList(),
        /** Apply-only. Target every clip on this track id. */
        val trackId: String? = null,
        /** Apply-only. Target every video clip in the project. */
        val allVideoClips: Boolean = false,
    )

    @Serializable data class Skipped(
        val clipId: String,
        val reason: String,
    )

    @Serializable data class RemoveItemResult(
        val clipId: String,
        val removedCount: Int,
        val remainingFilterCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val filterName: String,
        val snapshotId: String,
        /** Apply-only: ids of clips that received the filter. Empty on remove. */
        val appliedClipIds: List<String> = emptyList(),
        /** Apply-only: clips excluded by selector resolution. Empty on remove. */
        val skipped: List<Skipped> = emptyList(),
        /** Remove-only: per-clip removal counts. Empty on apply. */
        val removed: List<RemoveItemResult> = emptyList(),
        /** Remove-only: sum of `removedCount`. Zero on apply. */
        val totalRemoved: Int = 0,
    )

    override val id: String = "filter_action"
    override val helpText: String =
        "Apply or remove named filters on video clips in one atomic batch. " +
            "Pick `action=\"apply\"` + `filterName` (brightness, saturation, blur, vignette, lut, …) " +
            "with exactly one selector — `clipIds` (explicit list, single-clip = 1-element), " +
            "`trackId` (every clip on one track), or `allVideoClips=true`. Non-video clips skipped " +
            "silently; missing clipIds reported in `skipped`. Pick `action=\"remove\"` + " +
            "`filterName` + required `clipIds` to drop every filter with that name from each clip " +
            "— idempotent per clip (zero matches is fine) but unresolvable / non-video clipIds " +
            "abort the whole batch. Both actions emit one timeline snapshot per call."
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
                put("description", "`apply` to attach a filter, `remove` to drop filters by name.")
                put("enum", JsonArray(listOf(JsonPrimitive("apply"), JsonPrimitive("remove"))))
            }
            putJsonObject("filterName") {
                put("type", "string")
                put("description", "Engine-specific filter name (brightness, saturation, blur, vignette, lut, …).")
            }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Apply-only. Numeric parameters (e.g. {\"intensity\": 0.5}).")
                putJsonObject("additionalProperties") { put("type", "number") }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put(
                    "description",
                    "Apply: single-clip = 1-element list; mutually exclusive with trackId / allVideoClips. " +
                        "Remove: required list of video clip ids.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Apply-only. Target every video clip on this track id. Mutually exclusive with clipIds / allVideoClips.",
                )
            }
            putJsonObject("allVideoClips") {
                put("type", "boolean")
                put(
                    "description",
                    "Apply-only. Target every video clip in the project. Mutually exclusive with clipIds / trackId.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("filterName"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "apply" -> executeApply(pid, input, ctx)
            "remove" -> executeRemove(pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: apply, remove",
            )
        }
    }

    private suspend fun executeApply(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val selectorCount = listOf(
            input.clipIds.isNotEmpty(),
            !input.trackId.isNullOrBlank(),
            input.allVideoClips,
        ).count { it }
        require(selectorCount == 1) {
            "exactly one of clipIds / trackId / allVideoClips must be provided (got $selectorCount)"
        }

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
                    action = "apply",
                    filterName = input.filterName,
                    snapshotId = "",
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
                action = "apply",
                filterName = input.filterName,
                snapshotId = snapshotId.value,
                appliedClipIds = appliedClipIds,
                skipped = skipped,
            ),
        )
    }

    private suspend fun executeRemove(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty" }
        require(input.filterName.isNotBlank()) { "filterName must not be blank" }
        val results = mutableListOf<RemoveItemResult>()

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
                results += RemoveItemResult(
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
                action = "remove",
                filterName = input.filterName,
                snapshotId = snapshotId.value,
                removed = results,
                totalRemoved = total,
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
