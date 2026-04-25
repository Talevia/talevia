package io.talevia.core.tool.builtin.video.filter

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.action.ActionVerb
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.emitTimelineSnapshot

/**
 * Per-`execute()` resolved-context object for [FilterActionTool] —
 * carries the `ProjectStore` and the project this verb was scoped to.
 *
 * Built once at the top of [FilterActionTool.execute] (after
 * `ctx.resolveProjectId(input.projectId)`) and threaded into every
 * [ActionVerb.run] so per-verb handlers don't re-resolve.
 */
internal data class FilterActionDispatchContext(
    val store: ProjectStore,
    val projectId: ProjectId,
)

/**
 * Type alias for filter-side [ActionVerb] — fixes the dispatcher-wide
 * `Input` / `Output` types so each impl just declares its run lambda.
 */
internal typealias FilterActionVerb =
    ActionVerb<FilterActionTool.Input, FilterActionTool.Output, FilterActionDispatchContext>

/**
 * Cycle-161 plugin-shape registry for the 3 verbs on `filter_action` —
 * `apply` / `remove` / `apply_lut`. Each entry wraps the existing
 * `executeX(...)` body so the migration is shape-only — no behaviour
 * change, no schema change, no LLM-visible diff.
 *
 * Adding a new verb after this cycle: drop a new
 * `internal object FooFilterActionVerb : FilterActionVerb { ... }`
 * into this file (or its own sibling), append it to
 * [FILTER_ACTION_VERBS], add the action-id to the `enum` array in
 * [FilterActionTool.inputSchema], and you're done — no more editing the
 * dispatcher's `when` arm.
 *
 * **Migration recipe for the other 7 action dispatchers** (the bullet's
 * follow-up cycles, chunk one per cycle so each commit stays reviewable):
 *  1. `ClipActionTool` — 9 verbs (add / remove / split / trim /
 *     replace / set_speed / fade_audio / set_volume / edit_text);
 *     dispatch context = `(ProjectStore, ProjectId)`. Largest
 *     dispatcher; the per-verb files already exist as separate
 *     `*Handlers.kt` siblings, so the migration mostly wires them up.
 *  2. `TrackActionTool` — 4 verbs (add / remove / duplicate / reorder);
 *     dispatch context = `(ProjectStore, ProjectId)`.
 *  3. `SourceNodeActionTool` — N verbs across the source DAG; dispatch
 *     context = `(ProjectStore, ProjectId)`.
 *  4. `SessionActionTool` — 14 verbs (create / archive / restore /
 *     export / revert / compact / etc.); dispatch context =
 *     `(SessionStore, ProviderRegistry?, Session)`. The compact verb
 *     reaches into ProviderRegistry — that's the bigger context tuple.
 *  5. `ClipSetActionTool` — bulk set ops; dispatch context =
 *     `(ProjectStore, ProjectId)`.
 *  6. `ProjectActionTool` — fork / pin / pin-list / clear /
 *     export-snapshot; dispatch context = `(ProjectStore, FileSystem?)`.
 *  7. `ProjectMaintenanceActionTool` / `ProjectPinActionTool` /
 *     `ProjectSnapshotActionTool` — three smaller dispatchers with
 *     overlapping context shapes; can fold into a single migration cycle.
 *
 * Each future migration is mechanical (wrap existing handler) +
 * verifiable (existing tests pass byte-identical).
 */
internal val FILTER_ACTION_VERBS: List<FilterActionVerb> = listOf(
    ApplyFilterActionVerb,
    RemoveFilterActionVerb,
    ApplyLutFilterActionVerb,
)

/** Indexed lookup for `FilterActionTool.execute()`. */
internal val FILTER_ACTION_VERBS_BY_ID: Map<String, FilterActionVerb> =
    FILTER_ACTION_VERBS.associateBy { it.id }

internal object ApplyFilterActionVerb : FilterActionVerb {
    override val id: String = "apply"
    override suspend fun run(
        input: FilterActionTool.Input,
        ctx: ToolContext,
        dispatchContext: FilterActionDispatchContext,
    ): ToolResult<FilterActionTool.Output> {
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

        val pid = dispatchContext.projectId
        val appliedClipIds = mutableListOf<String>()
        val skipped = mutableListOf<FilterActionTool.Skipped>()

        val updated = dispatchContext.store.mutate(pid) { project ->
            val requestedIds = input.clipIds.toSet()
            if (requestedIds.isNotEmpty()) {
                val allClipsByKind = project.timeline.tracks.flatMap { track ->
                    track.clips.map { it.id.value to (it is Clip.Video) }
                }.toMap()
                for (id in requestedIds) {
                    when (allClipsByKind[id]) {
                        null -> skipped += FilterActionTool.Skipped(id, "clip not found")
                        false -> skipped += FilterActionTool.Skipped(
                            id,
                            "clip is not a video clip (apply_filter skips text/audio)",
                        )
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
                data = FilterActionTool.Output(
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
            data = FilterActionTool.Output(
                projectId = pid.value,
                action = "apply",
                filterName = input.filterName,
                snapshotId = snapshotId.value,
                appliedClipIds = appliedClipIds,
                skipped = skipped,
            ),
        )
    }
}

internal object RemoveFilterActionVerb : FilterActionVerb {
    override val id: String = "remove"
    override suspend fun run(
        input: FilterActionTool.Input,
        ctx: ToolContext,
        dispatchContext: FilterActionDispatchContext,
    ): ToolResult<FilterActionTool.Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty" }
        require(input.filterName.isNotBlank()) { "filterName must not be blank" }
        val pid = dispatchContext.projectId
        val results = mutableListOf<FilterActionTool.RemoveItemResult>()

        val updated = dispatchContext.store.mutate(pid) { project ->
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
                results += FilterActionTool.RemoveItemResult(
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
            data = FilterActionTool.Output(
                projectId = pid.value,
                action = "remove",
                filterName = input.filterName,
                snapshotId = snapshotId.value,
                removed = results,
                totalRemoved = total,
            ),
        )
    }
}

internal object ApplyLutFilterActionVerb : FilterActionVerb {
    override val id: String = "apply_lut"
    override suspend fun run(
        input: FilterActionTool.Input,
        ctx: ToolContext,
        dispatchContext: FilterActionDispatchContext,
    ): ToolResult<FilterActionTool.Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty when action=apply_lut" }
        val lutArg = input.lutAssetId?.takeIf { it.isNotBlank() }
        val styleArg = input.styleBibleId?.takeIf { it.isNotBlank() }
        require((lutArg == null) xor (styleArg == null)) {
            "exactly one of lutAssetId or styleBibleId must be provided"
        }

        val pid = dispatchContext.projectId
        var resolvedLutId: AssetId? = null
        val results = mutableListOf<FilterActionTool.LutItemResult>()

        val updated = dispatchContext.store.mutate(pid) { project ->
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
                results += FilterActionTool.LutItemResult(clipId = clipId, filterCount = newFilters.size)
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val sourceNote = if (styleArg != null) " (from style_bible '$styleArg')" else ""
        return ToolResult(
            title = "apply LUT × ${results.size}",
            outputForLlm = "Applied LUT '${resolvedLutId!!.value}'$sourceNote to ${results.size} clip(s). " +
                "Snapshot: ${snapshotId.value}",
            data = FilterActionTool.Output(
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
