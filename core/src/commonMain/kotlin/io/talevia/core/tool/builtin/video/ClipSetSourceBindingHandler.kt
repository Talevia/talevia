package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `action="set_sourceBinding"` handler. Absorbed into [ClipActionTool]
 * in cycle 44 (`debt-tool-consolidation-clip-action-phase1`).
 *
 * Set-swap semantics: replaces the clip's `sourceBinding` set entirely
 * with the provided one. Pre-commit guard that every new id exists in
 * `project.source.byId` — dangling bindings would silently stale the
 * clip forever.
 */

internal suspend fun executeClipSetSourceBinding(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.sourceBindingItems
        ?: error("action=set_sourceBinding requires `sourceBindingItems`")
    rejectForeignClipActionFields("set_sourceBinding", input)
    require(items.isNotEmpty()) { "sourceBindingItems must not be empty" }

    val results = mutableListOf<ClipActionTool.SourceBindingResult>()
    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val newBindingSet = item.sourceBinding.map { SourceNodeId(it) }.toSet()
            val missing = newBindingSet.filter { it !in project.source.byId }
            require(missing.isEmpty()) {
                "sourceBindingItems[$idx] (${item.clipId}): unknown source node ids: " +
                    missing.joinToString(", ") { it.value }
            }

            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error(
                "sourceBindingItems[$idx]: clip ${item.clipId} not found in project ${pid.value}",
            )
            val (track, clip) = hit
            val previousBinding = clip.sourceBinding.map { it.value }.sorted()

            val rebound: Clip = when (clip) {
                is Clip.Video -> clip.copy(sourceBinding = newBindingSet)
                is Clip.Audio -> clip.copy(sourceBinding = newBindingSet)
                is Clip.Text -> clip.copy(sourceBinding = newBindingSet)
            }
            val rebuilt = track.clips.map { if (it.id == clip.id) rebound else it }
            tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
            results += ClipActionTool.SourceBindingResult(
                clipId = item.clipId,
                previousBinding = previousBinding,
                newBinding = newBindingSet.map { it.value }.sorted(),
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }
    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "rebind × ${results.size}",
        outputForLlm = "Rebound source bindings on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "set_sourceBinding",
            snapshotId = snapshotId.value,
            sourceBindingResults = results,
        ),
    )
}
