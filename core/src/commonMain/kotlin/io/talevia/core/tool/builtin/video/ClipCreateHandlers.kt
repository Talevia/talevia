package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create-verb handlers extracted from [ClipActionTool] — the two actions
 * that produce new [ClipId] values (`add`, `duplicate`).
 *
 * **Why split by create-verb vs mutate-verb axis?** The six clip actions
 * fall into two natural subtypes: `add` + `duplicate` mint fresh clip ids,
 * everything else rewrites existing clips. Create handlers share
 * `pickVideoTrack` / `cloneClip` / `Uuid.random()` plumbing; mutate
 * handlers share `firstNotNullOfOrNull { t -> t.clips.firstOrNull { ... } }`
 * lookup plumbing. Keeping the two groups in separate files means the
 * phase-3 Replace/Fade fold (queued as
 * `debt-video-clip-consolidate-verbs-phase-3`) can land each verb in its
 * natural home without pushing either file past 500 LOC.
 */

@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeClipAdd(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.addItems ?: error("action=add requires `addItems`")
    rejectForeignClipActionFields("add", input)
    require(items.isNotEmpty()) { "addItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.sourceStartSeconds >= 0.0) {
            "addItems[$idx] (asset ${item.assetId}): sourceStartSeconds must be >= 0"
        }
        item.timelineStartSeconds?.let {
            require(it >= 0.0) {
                "addItems[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
            }
        }
        item.durationSeconds?.let {
            require(it > 0.0) {
                "addItems[$idx] (asset ${item.assetId}): durationSeconds must be > 0"
            }
        }
    }

    val results = mutableListOf<ClipActionTool.AddResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val asset = project.assets.firstOrNull { it.id.value == item.assetId }
                ?: error("addItems[$idx]: asset ${item.assetId} not found; import_media first.")
            val sourceStart: Duration = item.sourceStartSeconds.seconds
            require(sourceStart < asset.metadata.duration) {
                "addItems[$idx] (asset ${item.assetId}): sourceStartSeconds ${item.sourceStartSeconds} exceeds asset " +
                    "duration ${asset.metadata.duration.inWholeMilliseconds / 1000.0}s"
            }
            val remaining = asset.metadata.duration - sourceStart
            val clipDuration: Duration = (item.durationSeconds?.seconds ?: remaining).coerceAtMost(remaining)
            require(clipDuration > Duration.ZERO) {
                "addItems[$idx] (asset ${item.assetId}): clip duration must be > 0"
            }

            val videoTrack = pickVideoTrack(tracks, item.trackId)
            val tlStart = item.timelineStartSeconds?.seconds
                ?: videoTrack.clips.maxOfOrNull { it.timeRange.end }
                ?: Duration.ZERO
            require(tlStart >= Duration.ZERO) {
                "addItems[$idx] (asset ${item.assetId}): timelineStartSeconds must be >= 0"
            }

            val clipId = ClipId(Uuid.random().toString())
            val newClip = Clip.Video(
                id = clipId,
                timeRange = TimeRange(tlStart, clipDuration),
                sourceRange = TimeRange(sourceStart, clipDuration),
                assetId = asset.id,
            )
            val newClips = (videoTrack.clips + newClip).sortedBy { it.timeRange.start }
            val newTrack = videoTrack.copy(clips = newClips)
            tracks = upsertTrackPreservingOrder(tracks, newTrack)

            results += ClipActionTool.AddResult(
                clipId = clipId.value,
                timelineStartSeconds = tlStart.toDouble(DurationUnit.SECONDS),
                timelineEndSeconds = (tlStart + clipDuration).toDouble(DurationUnit.SECONDS),
                trackId = newTrack.id.value,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeClipBoundDuration(tracks)))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "add ${results.size} clip(s)",
        outputForLlm = "Added ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "add",
            snapshotId = snapshotId.value,
            added = results,
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeClipDuplicate(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.duplicateItems ?: error("action=duplicate requires `duplicateItems`")
    rejectForeignClipActionFields("duplicate", input)
    require(items.isNotEmpty()) { "duplicateItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.timelineStartSeconds >= 0.0) {
            "duplicateItems[$idx] (${item.clipId}): timelineStartSeconds must be >= 0 (got ${item.timelineStartSeconds})"
        }
    }

    val results = mutableListOf<ClipActionTool.DuplicateResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val originalTrack = tracks.firstOrNull { t ->
                t.clips.any { it.id.value == item.clipId }
            } ?: error("duplicateItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val original = originalTrack.clips.first { it.id.value == item.clipId }

            val targetTrack = if (item.trackId == null || item.trackId == originalTrack.id.value) {
                originalTrack
            } else {
                val candidate = tracks.firstOrNull { it.id.value == item.trackId }
                    ?: error(
                        "duplicateItems[$idx] (${item.clipId}): trackId ${item.trackId} not found in project ${pid.value}",
                    )
                require(trackKindOf(candidate) == trackKindOf(originalTrack)) {
                    "duplicateItems[$idx] (${item.clipId}): trackId ${item.trackId} is ${trackKindOf(candidate)} " +
                        "but source clip is ${trackKindOf(originalTrack)}"
                }
                candidate
            }

            val newStart: Duration = item.timelineStartSeconds.seconds
            val newClipId = ClipId(Uuid.random().toString())
            val cloned = cloneClip(original, newClipId, newStart)
            val newEndSeconds = (newStart + original.timeRange.duration).toDouble(DurationUnit.SECONDS)

            tracks = tracks.map { t ->
                if (t.id == targetTrack.id) {
                    withClips(t, (t.clips + cloned).sortedBy { it.timeRange.start })
                } else {
                    t
                }
            }
            results += ClipActionTool.DuplicateResult(
                originalClipId = item.clipId,
                newClipId = newClipId.value,
                sourceTrackId = originalTrack.id.value,
                targetTrackId = targetTrack.id.value,
                timelineStartSeconds = item.timelineStartSeconds,
                timelineEndSeconds = newEndSeconds,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks, duration = recomputeClipBoundDuration(tracks)))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "duplicate × ${results.size}",
        outputForLlm = "Duplicated ${results.size} clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "duplicate",
            snapshotId = snapshotId.value,
            duplicated = results,
        ),
    )
}
