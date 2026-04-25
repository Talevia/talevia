package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Place-axis mutate-verb handlers — the verbs that change WHERE a
 * clip is on the timeline (`remove`, `move`). Sibling of
 * [executeClipSplit] / [executeClipTrim] / [executeClipReplace] in
 * `ClipMutateShapeHandlers.kt` which handle WHAT a clip is.
 *
 * Split out from the joint `ClipMutateHandlers.kt` per backlog
 * `debt-split-clip-mutate-handlers`: the original file was 413 LOC
 * across 5 verbs; the place / shape axis maps cleanly onto user
 * intent ("I want to move this" vs "I want to change this") and
 * each file lands well under the R.5.4 watermark.
 *
 * Each handler accepts the minimal dependencies explicitly —
 * `(store, pid, input, ctx)` — so it reads like a stand-alone
 * procedure rather than leaning on the tool instance.
 */

internal suspend fun executeClipRemove(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val clipIds = input.clipIds ?: error("action=remove requires `clipIds`")
    rejectForeignClipActionFields("remove", input)
    require(clipIds.isNotEmpty()) { "clipIds must not be empty" }

    val results = mutableListOf<ClipActionTool.RemoveResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        clipIds.forEachIndexed { idx, clipId ->
            var foundTrackId: String? = null
            var removedRange: TimeRange? = null
            var shifted = 0
            tracks = tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == clipId }
                if (target == null) {
                    track
                } else {
                    foundTrackId = track.id.value
                    removedRange = target.timeRange
                    val keep = track.clips.filter { it.id.value != clipId }
                    val shiftedClips = if (input.ripple) {
                        keep.map { clip ->
                            if (clip.timeRange.start >= target.timeRange.end) {
                                shifted += 1
                                clip.shiftStart(-target.timeRange.duration)
                            } else {
                                clip
                            }
                        }
                    } else {
                        keep
                    }
                    withClips(track, shiftedClips)
                }
            }
            if (foundTrackId == null) {
                error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
            }
            results += ClipActionTool.RemoveResult(
                clipId = clipId,
                trackId = foundTrackId!!,
                durationSeconds = removedRange!!.duration.inWholeMilliseconds / 1000.0,
                shiftedClipCount = shifted,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    val summary = buildString {
        append("Removed ${results.size} clip(s)")
        if (input.ripple) append(" (rippled)")
        append(". Timeline snapshot: ${snapshotId.value}")
    }
    return ToolResult(
        title = "remove ${results.size} clip(s)",
        outputForLlm = summary,
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "remove",
            snapshotId = snapshotId.value,
            removed = results,
            rippled = input.ripple,
        ),
    )
}

internal suspend fun executeClipMove(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.moveItems ?: error("action=move requires `moveItems`")
    rejectForeignClipActionFields("move", input)
    require(items.isNotEmpty()) { "moveItems must not be empty" }
    items.forEachIndexed { idx, item ->
        if (item.timelineStartSeconds == null && item.toTrackId == null) {
            error("moveItems[$idx] (${item.clipId}): at least one of timelineStartSeconds / toTrackId must be set")
        }
        item.timelineStartSeconds?.let {
            if (it < 0.0) error("moveItems[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got $it)")
        }
    }

    val results = mutableListOf<ClipActionTool.MoveResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val sourceHit = tracks
                .firstNotNullOfOrNull { t -> t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it } }
                ?: error("moveItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (sourceTrack, clip) = sourceHit

            val targetTrack: Track = when {
                item.toTrackId == null || item.toTrackId == sourceTrack.id.value -> sourceTrack
                else -> tracks.firstOrNull { it.id.value == item.toTrackId }
                    ?: error("moveItems[$idx] (${item.clipId}): target track ${item.toTrackId} not found")
            }

            if (targetTrack.id != sourceTrack.id && !isKindCompatible(clip, targetTrack)) {
                error(
                    "moveItems[$idx] (${item.clipId}): cannot move ${clipKindOf(clip)} clip onto " +
                        "${trackKindOf(targetTrack)} track ${targetTrack.id.value}.",
                )
            }

            val oldStartSeconds = clip.timeRange.start.toDouble(DurationUnit.SECONDS)
            val newStart: Duration = item.timelineStartSeconds?.seconds ?: clip.timeRange.start
            val moved = withTimeRange(clip, TimeRange(newStart, clip.timeRange.duration))

            tracks = if (targetTrack.id == sourceTrack.id) {
                tracks.map { track ->
                    if (track.id != sourceTrack.id) track else replaceClip(track, moved)
                }
            } else {
                tracks.map { track ->
                    when (track.id) {
                        sourceTrack.id -> withClips(track, track.clips.filterNot { it.id.value == clip.id.value })
                        targetTrack.id -> withClips(track, (track.clips + moved).sortedBy { it.timeRange.start })
                        else -> track
                    }
                }
            }

            results += ClipActionTool.MoveResult(
                clipId = item.clipId,
                fromTrackId = sourceTrack.id.value,
                toTrackId = targetTrack.id.value,
                oldStartSeconds = oldStartSeconds,
                newStartSeconds = newStart.toDouble(DurationUnit.SECONDS),
                changedTrack = sourceTrack.id.value != targetTrack.id.value,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeClipBoundDuration(tracks)))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "move ${results.size} clip(s)",
        outputForLlm = "Moved ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "move",
            snapshotId = snapshotId.value,
            moved = results,
        ),
    )
}
