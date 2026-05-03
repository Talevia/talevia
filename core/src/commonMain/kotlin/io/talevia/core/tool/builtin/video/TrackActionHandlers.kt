package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Per-verb handlers + helpers for [TimelineActionTool]. Extracted from the
 * dispatcher so each new verb adds its body here rather than padding the
 * tool's own class with another `executeX` method — see cycle's commit
 * for the LOC growth axis (every action verb adds 5–15 LOC of params to
 * `Input` + ~50 LOC of handler).
 *
 * All four handlers share the contract:
 *  - take `(store, input, ctx)` so they're stateless w.r.t. the calling
 *    [TimelineActionTool] instance — no implicit `this` capture.
 *  - run a single `store.mutate` and emit a `Part.TimelineSnapshot`
 *    (so `revert_timeline` / undo can step back).
 *  - return a fully-populated [TimelineActionTool.Output] with `action`
 *    stamped — the dispatcher does no post-processing.
 *
 * Helpers ([generateCopyId], [cloneClip], [rebuildTrackWithNewId],
 * [trackKind]) are file-internal because no other call site exists today;
 * if a sibling tool ever needs them, promote to `internal` package-level
 * with a short doc.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeAdd(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    val trackKindRaw = input.trackKind
        ?: error("trackKind is required when action=add")
    val normalisedKind = trackKindRaw.trim().lowercase()
    require(normalisedKind in ACCEPTED_KINDS) {
        "unknown trackKind '$trackKindRaw'; accepted: ${ACCEPTED_KINDS.joinToString()}"
    }
    val requestedId = input.trackId?.trim()?.takeIf { it.isNotEmpty() }
    val newId = requestedId ?: Uuid.random().toString()

    var totalCount = 0
    val updated = store.mutate(pid) { project ->
        if (project.timeline.tracks.any { it.id.value == newId }) {
            error("trackId '$newId' already exists in project ${pid.value}")
        }
        val tid = TrackId(newId)
        val newTrack: Track = when (normalisedKind) {
            "video" -> Track.Video(id = tid)
            "audio" -> Track.Audio(id = tid)
            "subtitle" -> Track.Subtitle(id = tid)
            "effect" -> Track.Effect(id = tid)
            else -> error("unreachable")
        }
        val tracks = project.timeline.tracks + newTrack
        totalCount = tracks.size
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "add $normalisedKind track",
        outputForLlm = "Added empty $normalisedKind track $newId to project ${pid.value} " +
            "($totalCount total track(s)). Timeline snapshot: ${snapshotId.value}",
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "add_track",
            trackId = newId,
            trackKind = normalisedKind,
            totalTrackCount = totalCount,
            snapshotId = snapshotId.value,
        ),
    )
}

internal suspend fun executeRemove(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    require(input.trackIds.isNotEmpty()) { "trackIds must not be empty" }
    val results = mutableListOf<TimelineActionTool.RemoveItemResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        input.trackIds.forEachIndexed { idx, trackId ->
            val target = tracks.firstOrNull { it.id.value == trackId }
                ?: error("trackIds[$idx] '$trackId' not found in project ${pid.value}")
            if (target.clips.isNotEmpty() && !input.force) {
                error(
                    "trackIds[$idx] '$trackId' has ${target.clips.size} clip(s); pass " +
                        "force=true to drop the track(s) and their clips, or remove the clips first",
                )
            }
            val kind = trackKind(target)
            val droppedClips = target.clips.size
            tracks = tracks.filter { it.id.value != trackId }
            results += TimelineActionTool.RemoveItemResult(
                trackId = trackId,
                trackKind = kind,
                droppedClipCount = droppedClips,
            )
        }
        val duration = tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO
        project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
    }
    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    val summary = buildString {
        append("Dropped ${results.size} track(s)")
        val totalClips = results.sumOf { it.droppedClipCount }
        if (totalClips > 0) append(" with $totalClips clip(s)")
        append(". Timeline snapshot: ${snapshotId.value}")
    }
    return ToolResult(
        title = "remove ${results.size} track(s)",
        outputForLlm = summary,
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "remove_track",
            results = results,
            forced = input.force,
            snapshotId = snapshotId.value,
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeDuplicate(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    require(input.items.isNotEmpty()) { "items must not be empty when action=duplicate" }
    val results = mutableListOf<TimelineActionTool.DuplicateItemResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        input.items.forEachIndexed { idx, item ->
            val source = tracks.firstOrNull { it.id.value == item.sourceTrackId }
                ?: error(
                    "items[$idx]: sourceTrackId '${item.sourceTrackId}' not found in project ${pid.value}",
                )

            val existingIds = tracks.map { it.id.value }.toSet()
            val chosenId = if (item.newTrackId != null) {
                val requested = item.newTrackId
                require(requested !in existingIds) {
                    "items[$idx] (${item.sourceTrackId}): newTrackId '$requested' collides with an " +
                        "existing track in project ${pid.value}"
                }
                requested
            } else {
                generateCopyId(item.sourceTrackId, existingIds)
            }

            val clonedClips = source.clips.map { cloneClip(it) }
            val cloned: Track = rebuildTrackWithNewId(source, TrackId(chosenId), clonedClips)

            tracks = tracks + cloned
            results += TimelineActionTool.DuplicateItemResult(
                sourceTrackId = item.sourceTrackId,
                newTrackId = chosenId,
                clipCount = clonedClips.size,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "duplicate ${results.size} track(s)",
        outputForLlm = "Duplicated ${results.size} track(s). Snapshot: ${snapshotId.value}",
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "duplicate_track",
            duplicateResults = results,
            snapshotId = snapshotId.value,
        ),
    )
}

internal suspend fun executeReorder(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    require(input.trackIds.isNotEmpty()) {
        "trackIds must not be empty when action=reorder — nothing to reorder. " +
            "Omit this tool entirely if the order is already correct."
    }
    val dedup = input.trackIds.toSet()
    require(dedup.size == input.trackIds.size) {
        "trackIds contains duplicates: ${input.trackIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}"
    }

    var newOrderOut: List<String> = emptyList()

    val updated = store.mutate(pid) { project ->
        val tracks = project.timeline.tracks
        val byId = tracks.associateBy { it.id.value }

        val missing = input.trackIds.filter { it !in byId }
        require(missing.isEmpty()) {
            "Unknown track id(s): ${missing.joinToString(", ")}. Known: ${byId.keys.joinToString(", ")}"
        }

        val front = input.trackIds.map { byId.getValue(it) }
        val frontIdSet = input.trackIds.map { TrackId(it) }.toSet()
        val tail = tracks.filterNot { it.id in frontIdSet } // preserves existing relative order
        val reordered = front + tail
        newOrderOut = reordered.map { it.id.value }
        project.copy(timeline = project.timeline.copy(tracks = reordered))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "reorder tracks (${input.trackIds.size} pinned)",
        outputForLlm = "Track order is now: ${newOrderOut.joinToString(", ")}. " +
            "Timeline snapshot: ${snapshotId.value}",
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "reorder_track",
            newOrder = newOrderOut,
            snapshotId = snapshotId.value,
        ),
    )
}

internal val ACCEPTED_KINDS: Set<String> = setOf("video", "audio", "subtitle", "effect")

private fun generateCopyId(sourceId: String, existing: Set<String>): String {
    var n = 1
    while (true) {
        val candidate = "$sourceId-copy-$n"
        if (candidate !in existing) return candidate
        n++
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun cloneClip(original: Clip): Clip {
    val newId = ClipId(Uuid.random().toString())
    return when (original) {
        is Clip.Video -> original.copy(id = newId)
        is Clip.Audio -> original.copy(id = newId)
        is Clip.Text -> original.copy(id = newId)
    }
}

private fun rebuildTrackWithNewId(source: Track, newId: TrackId, clips: List<Clip>): Track = when (source) {
    is Track.Video -> Track.Video(id = newId, clips = clips)
    is Track.Audio -> Track.Audio(id = newId, clips = clips)
    is Track.Subtitle -> Track.Subtitle(id = newId, clips = clips)
    is Track.Effect -> Track.Effect(id = newId, clips = clips)
}

private fun trackKind(track: Track): String = when (track) {
    is Track.Video -> "video"
    is Track.Audio -> "audio"
    is Track.Subtitle -> "subtitle"
    is Track.Effect -> "effect"
}
