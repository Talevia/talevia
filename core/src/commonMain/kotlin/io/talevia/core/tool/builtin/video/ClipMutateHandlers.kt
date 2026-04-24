package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Mutate-verb handlers extracted from [ClipActionTool] — the four actions
 * that rewrite existing clips in place (`remove`, `move`, `split`,
 * `trim`). Counterpart to [ClipCreateHandlers]; together they replace the
 * six `execute*` methods that used to live directly on [ClipActionTool].
 *
 * Each handler accepts the minimal dependencies explicitly — `(store, pid,
 * input, ctx)` — so it reads like a stand-alone procedure rather than
 * leaning on the tool instance. That keeps the file testable in isolation
 * if we ever want to without reaching into `ClipActionTool` fixtures.
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

internal suspend fun executeClipFade(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.fadeItems ?: error("action=fade requires `fadeItems`")
    rejectForeignClipActionFields("fade", input)
    require(items.isNotEmpty()) { "fadeItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.fadeInSeconds != null || item.fadeOutSeconds != null) {
            "fadeItems[$idx] (${item.clipId}): at least one of fadeInSeconds / fadeOutSeconds required"
        }
        item.fadeInSeconds?.let {
            require(it.isFinite() && it >= 0f) {
                "fadeItems[$idx] (${item.clipId}): fadeInSeconds must be finite and >= 0 (got $it)"
            }
        }
        item.fadeOutSeconds?.let {
            require(it.isFinite() && it >= 0f) {
                "fadeItems[$idx] (${item.clipId}): fadeOutSeconds must be finite and >= 0 (got $it)"
            }
        }
    }

    val results = mutableListOf<ClipActionTool.FadeResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error("fadeItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit
            val audio = clip as? Clip.Audio ?: error(
                "fadeItems[$idx]: action=fade only applies to audio clips; clip ${item.clipId} " +
                    "is a ${clip::class.simpleName}.",
            )
            val oldIn = audio.fadeInSeconds
            val oldOut = audio.fadeOutSeconds
            val newIn = item.fadeInSeconds ?: oldIn
            val newOut = item.fadeOutSeconds ?: oldOut
            val clipDurationSeconds = audio.timeRange.duration.toDouble(DurationUnit.SECONDS).toFloat()
            require(newIn + newOut <= clipDurationSeconds + 1e-3f) {
                "fadeItems[$idx] (${item.clipId}): fadeIn ($newIn) + fadeOut ($newOut) would exceed " +
                    "clip duration ($clipDurationSeconds); fades would overlap."
            }
            val rebuilt = track.clips.map { c ->
                if (c.id == audio.id) audio.copy(fadeInSeconds = newIn, fadeOutSeconds = newOut) else c
            }
            val newTrack: Track = withClips(track, rebuilt)
            tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
            results += ClipActionTool.FadeResult(
                clipId = item.clipId,
                trackId = track.id.value,
                oldFadeInSeconds = oldIn,
                newFadeInSeconds = newIn,
                oldFadeOutSeconds = oldOut,
                newFadeOutSeconds = newOut,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "fade × ${results.size}",
        outputForLlm = "Set fades on ${results.size} audio clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "fade",
            snapshotId = snapshotId.value,
            faded = results,
        ),
    )
}
