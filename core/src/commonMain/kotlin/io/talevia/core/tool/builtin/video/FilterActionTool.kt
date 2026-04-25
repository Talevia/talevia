package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.asStyleBible
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
 * behaviour-exactly. Cycle 153 reversed the prior "ApplyLutTool stays
 * separate" decision and absorbed it as `action="apply_lut"`: the
 * LUT-specific semantics (style_bible resolution + sourceBinding
 * cascade) live in their own dispatch arm rather than muddling the
 * shared apply/remove path, so the filter_action surface stays clean
 * while still saving the standalone tool's spec entry.
 *
 * ## Input contract
 *
 * - `action` = `"apply"`, `"remove"`, or `"apply_lut"` (required).
 * - apply / remove: `filterName` required.
 * - apply_lut: `clipIds` required + exactly one of `lutAssetId` or
 *   `styleBibleId` (mutually exclusive). Each affected clip gets a
 *   `Filter(name="lut", assetId=…)` appended; the style_bible path
 *   also adds the style_bible nodeId to each clip's `sourceBinding`.
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
        /** `"apply"`, `"remove"`, or `"apply_lut"`. Case-sensitive. */
        val action: String,
        /**
         * Required for `apply` / `remove`; ignored for `apply_lut` (the
         * filter name is hard-coded to `"lut"` so the engine layer can
         * dispatch it through the LUT-specific handler).
         */
        val filterName: String = "",
        /** Apply-only. Numeric parameters attached to the filter (e.g. `{"intensity": 0.5}`). */
        val params: Map<String, Float> = emptyMap(),
        /**
         * Both paths. Apply: one of three selectors (mutually exclusive with
         * `trackId` / `allVideoClips`). Remove: required list of video clips.
         * Apply-LUT: required list of video clips (no track / all-clips
         * selector — broadcasting is the caller's responsibility for now).
         */
        val clipIds: List<String> = emptyList(),
        /** Apply-only. Target every clip on this track id. */
        val trackId: String? = null,
        /** Apply-only. Target every video clip in the project. */
        val allVideoClips: Boolean = false,
        /**
         * `apply_lut` only. Asset id of an imported LUT (.cube / .3dl).
         * Mutually exclusive with [styleBibleId].
         */
        val lutAssetId: String? = null,
        /**
         * `apply_lut` only. Source-node id of a `core.consistency.style_bible`.
         * The node's `lutReference` is resolved at apply time. Each affected
         * clip also gets the style_bible's nodeId added to its
         * `sourceBinding` so future staleness machinery can propagate
         * edits through the DAG (VISION §3.3). Mutually exclusive with
         * [lutAssetId].
         */
        val styleBibleId: String? = null,
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

    @Serializable data class LutItemResult(
        val clipId: String,
        val filterCount: Int,
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
        /** Apply-LUT-only: resolved LUT asset id (echo from input or derived from style_bible). Empty on every other action. */
        val lutAssetId: String = "",
        /** Apply-LUT-only: per-clip LUT-application result. Empty on every other action. */
        val lutResults: List<LutItemResult> = emptyList(),
        /** Apply-LUT-only: echoes the input `styleBibleId` when the LUT was resolved through one. Null otherwise. */
        val lutStyleBibleId: String? = null,
    )

    override val id: String = "filter_action"
    override val helpText: String =
        "Apply or remove named filters on video clips atomically. " +
            "action=apply + filterName (brightness|saturation|blur|vignette|lut|…) + exactly one " +
            "selector (clipIds | trackId | allVideoClips=true); non-video clips skipped, missing " +
            "clipIds reported in `skipped`. action=remove + filterName + required clipIds; " +
            "idempotent per clip but unresolvable/non-video clipIds abort. " +
            "action=apply_lut + clipIds + exactly one of (lutAssetId | styleBibleId) attaches a 3D " +
            "LUT (.cube / .3dl) to one or many video clips; the style_bible path resolves " +
            "lutReference at apply time AND binds each clip to the style_bible nodeId so " +
            "future edits cascade through stale-clip detection. One snapshot per call."
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
                put(
                    "description",
                    "`apply` to attach a filter, `remove` to drop filters by name, " +
                        "`apply_lut` to attach a 3D LUT (.cube / .3dl) by asset id or via a " +
                        "style_bible nodeId.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(JsonPrimitive("apply"), JsonPrimitive("remove"), JsonPrimitive("apply_lut")),
                    ),
                )
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
            putJsonObject("lutAssetId") {
                put("type", "string")
                put(
                    "description",
                    "action=apply_lut only. Asset id of an imported LUT (.cube / .3dl). " +
                        "Mutually exclusive with styleBibleId.",
                )
            }
            putJsonObject("styleBibleId") {
                put("type", "string")
                put(
                    "description",
                    "action=apply_lut only. Source-node id of a core.consistency.style_bible. " +
                        "Its lutReference is resolved at apply time. Mutually exclusive with lutAssetId.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("action"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "apply" -> executeApply(pid, input, ctx)
            "remove" -> executeRemove(pid, input, ctx)
            "apply_lut" -> executeApplyLut(pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: apply, remove, apply_lut",
            )
        }
    }

    private suspend fun executeApply(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        require(input.filterName.isNotBlank()) {
            "filterName is required when action=apply"
        }
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

    private suspend fun executeApplyLut(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty when action=apply_lut" }
        val lutArg = input.lutAssetId?.takeIf { it.isNotBlank() }
        val styleArg = input.styleBibleId?.takeIf { it.isNotBlank() }
        require((lutArg == null) xor (styleArg == null)) {
            "exactly one of lutAssetId or styleBibleId must be provided"
        }

        var resolvedLutId: AssetId? = null
        val results = mutableListOf<LutItemResult>()

        val updated = store.mutate(pid) { project ->
            val lutId: AssetId = if (lutArg != null) {
                AssetId(lutArg)
            } else {
                val sid = SourceNodeId(styleArg!!)
                val node = project.source.byId[sid]
                    ?: error("style_bible node '${sid.value}' not found in project ${pid.value}")
                val style = node.asStyleBible()
                    ?: error("node '${sid.value}' exists but is not a style_bible (kind=${node.kind})")
                style.lutReference
                    ?: error(
                        "style_bible '${sid.value}' has no lutReference; set one by updating the node's body " +
                            "(source_query(select=node_detail) → set body.lutReference → update_source_node_body) first",
                    )
            }
            if (project.assets.none { it.id == lutId }) {
                error("LUT asset '${lutId.value}' not found in the project's asset catalog")
            }
            resolvedLutId = lutId

            var tracks = project.timeline.tracks
            input.clipIds.forEachIndexed { idx, clipId ->
                val hit = tracks.firstNotNullOfOrNull { t ->
                    t.clips.firstOrNull { it.id.value == clipId }?.let { t to it }
                } ?: error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
                val (track, clip) = hit
                if (clip !is Clip.Video) {
                    error(
                        "clipIds[$idx] ($clipId): apply_lut only supports video clips; " +
                            "clip is ${clip::class.simpleName}",
                    )
                }
                val newFilters = clip.filters + Filter(name = "lut", assetId = lutId)
                val newBinding = if (styleArg != null) clip.sourceBinding + SourceNodeId(styleArg) else clip.sourceBinding
                val replaced = clip.copy(filters = newFilters, sourceBinding = newBinding)
                val newTrack = replaceClipOnTrack(track, clip, replaced)
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += LutItemResult(clipId = clipId, filterCount = newFilters.size)
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val sourceNote = if (styleArg != null) " (from style_bible '$styleArg')" else ""
        return ToolResult(
            title = "apply LUT × ${results.size}",
            outputForLlm = "Applied LUT '${resolvedLutId!!.value}'$sourceNote to ${results.size} clip(s). " +
                "Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                action = "apply_lut",
                filterName = "lut",
                snapshotId = snapshotId.value,
                lutAssetId = resolvedLutId!!.value,
                lutResults = results,
                lutStyleBibleId = styleArg,
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
