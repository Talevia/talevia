package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Shape-axis mutate-verb handlers — the verbs that change WHAT a
 * clip is (`split`, `trim`, `replace`). Sibling of
 * [executeClipRemove] / [executeClipMove] in
 * `ClipMutatePlaceHandlers.kt` which handle WHERE a clip is on the
 * timeline.
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

internal suspend fun executeClipSplit(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.splitItems ?: error("action=split requires `splitItems`")
    rejectForeignClipActionFields("split", input)
    require(items.isNotEmpty()) { "splitItems must not be empty" }

    val results = mutableListOf<ClipActionTool.SplitResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            var found = false
            var leftId: ClipId? = null
            var rightId: ClipId? = null
            val splitAt = item.atTimelineSeconds.seconds
            tracks = tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == item.clipId }
                    ?: return@map track
                found = true
                if (splitAt <= target.timeRange.start || splitAt >= target.timeRange.end) {
                    error(
                        "splitItems[$idx] (${item.clipId}): split point ${item.atTimelineSeconds}s " +
                            "is outside clip ${target.timeRange.start}..${target.timeRange.end}",
                    )
                }
                val offset = splitAt - target.timeRange.start
                val (l, r) = splitClip(target, offset)
                leftId = l.id
                rightId = r.id
                withClips(
                    track,
                    (track.clips.filter { it.id != target.id } + listOf(l, r)).sortedBy { it.timeRange.start },
                )
            }
            if (!found) error("splitItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            results += ClipActionTool.SplitResult(
                originalClipId = item.clipId,
                leftClipId = leftId!!.value,
                rightClipId = rightId!!.value,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "split × ${results.size}",
        outputForLlm = "Split ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "split",
            snapshotId = snapshotId.value,
            split = results,
        ),
    )
}

internal suspend fun executeClipTrim(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.trimItems ?: error("action=trim requires `trimItems`")
    rejectForeignClipActionFields("trim", input)
    require(items.isNotEmpty()) { "trimItems must not be empty" }
    items.forEachIndexed { idx, item ->
        if (item.newSourceStartSeconds == null && item.newDurationSeconds == null) {
            error(
                "trimItems[$idx] (${item.clipId}): at least one of newSourceStartSeconds / newDurationSeconds required",
            )
        }
        item.newSourceStartSeconds?.let {
            require(it >= 0.0) {
                "trimItems[$idx] (${item.clipId}): newSourceStartSeconds must be >= 0 (got $it)"
            }
        }
        item.newDurationSeconds?.let {
            require(it > 0.0) {
                "trimItems[$idx] (${item.clipId}): newDurationSeconds must be > 0 (got $it)"
            }
        }
    }

    val results = mutableListOf<ClipActionTool.TrimResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { t ->
                t.clips.firstOrNull { it.id.value == item.clipId }?.let { t to it }
            } ?: error("trimItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit

            val (assetId, currentSourceRange) = when (clip) {
                is Clip.Video -> clip.assetId to clip.sourceRange
                is Clip.Audio -> clip.assetId to clip.sourceRange
                is Clip.Text -> error(
                    "trimItems[$idx] (${item.clipId}): action=trim cannot trim a text/subtitle clip; " +
                        "use add_subtitles to reset its time range.",
                )
            }
            val newSourceStart: Duration = item.newSourceStartSeconds?.seconds ?: currentSourceRange.start
            val newDuration: Duration = item.newDurationSeconds?.seconds ?: currentSourceRange.duration
            val assetDuration = project.assets.firstOrNull { it.id == assetId }?.metadata?.duration
                ?: error(
                    "trimItems[$idx] (${item.clipId}): asset ${assetId.value} bound to clip not found in project " +
                        "${pid.value} — import it (or restore the clip's source binding) first.",
                )
            require(newSourceStart < assetDuration) {
                "trimItems[$idx] (${item.clipId}): newSourceStartSeconds " +
                    "${newSourceStart.toDouble(DurationUnit.SECONDS)} exceeds asset duration " +
                    "${assetDuration.toDouble(DurationUnit.SECONDS)}s."
            }
            require(newSourceStart + newDuration <= assetDuration) {
                "trimItems[$idx] (${item.clipId}): trim window (start=" +
                    "${newSourceStart.toDouble(DurationUnit.SECONDS)}s + duration=" +
                    "${newDuration.toDouble(DurationUnit.SECONDS)}s) extends past asset duration " +
                    "${assetDuration.toDouble(DurationUnit.SECONDS)}s."
            }
            val timelineRange = TimeRange(clip.timeRange.start, newDuration)
            val sourceRange = TimeRange(newSourceStart, newDuration)
            val trimmed = withTrim(clip, timelineRange, sourceRange)
            tracks = tracks.map { t ->
                if (t.id == track.id) {
                    withClips(t, t.clips.map { if (it.id == clip.id) trimmed else it }.sortedBy { it.timeRange.start })
                } else {
                    t
                }
            }
            results += ClipActionTool.TrimResult(
                clipId = item.clipId,
                trackId = track.id.value,
                newSourceStartSeconds = newSourceStart.toDouble(DurationUnit.SECONDS),
                newDurationSeconds = newDuration.toDouble(DurationUnit.SECONDS),
                newTimelineEndSeconds = timelineRange.end.toDouble(DurationUnit.SECONDS),
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeClipBoundDuration(tracks)))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "trim ${results.size} clip(s)",
        outputForLlm = "Trimmed ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "trim",
            snapshotId = snapshotId.value,
            trimmed = results,
        ),
    )
}

internal suspend fun executeClipReplace(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.replaceItems ?: error("action=replace requires `replaceItems`")
    rejectForeignClipActionFields("replace", input)
    require(items.isNotEmpty()) { "replaceItems must not be empty" }

    val results = mutableListOf<ClipActionTool.ReplaceResult>()

    val updated = store.mutate(pid) { project ->
        items.forEachIndexed { idx, item ->
            if (project.assets.none { it.id.value == item.newAssetId }) {
                error(
                    "replaceItems[$idx] (${item.clipId}): asset ${item.newAssetId} not found in project " +
                        "${pid.value}; import or generate it first.",
                )
            }
        }
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val newAssetId = AssetId(item.newAssetId)
            val lockBinding: Set<SourceNodeId> =
                project.lockfile.findByAssetId(newAssetId)?.sourceBinding ?: emptySet()
            var previousAssetId: String? = null
            var found = false
            tracks = tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == item.clipId } ?: return@map track
                found = true
                val replacement: Clip = when (target) {
                    is Clip.Video -> {
                        previousAssetId = target.assetId.value
                        target.copy(assetId = newAssetId, sourceBinding = lockBinding)
                    }
                    is Clip.Audio -> {
                        previousAssetId = target.assetId.value
                        target.copy(assetId = newAssetId, sourceBinding = lockBinding)
                    }
                    is Clip.Text -> error(
                        "replaceItems[$idx] (${item.clipId}): action=replace does not apply to text clips " +
                            "(no underlying asset).",
                    )
                }
                withClips(track, track.clips.map { if (it.id == target.id) replacement else it })
            }
            if (!found) error("replaceItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            results += ClipActionTool.ReplaceResult(
                clipId = item.clipId,
                previousAssetId = previousAssetId ?: error("internal: previousAssetId not captured"),
                newAssetId = newAssetId.value,
                sourceBindingIds = lockBinding.map { it.value },
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "replace × ${results.size}",
        outputForLlm = "Replaced asset on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "replace",
            snapshotId = snapshotId.value,
            replaced = results,
        ),
    )
}
