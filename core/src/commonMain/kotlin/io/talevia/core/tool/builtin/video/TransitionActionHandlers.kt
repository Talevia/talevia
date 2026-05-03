package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Per-verb handlers + helpers for [TimelineActionTool]'s transition
 * verbs (`add_transition` / `remove_transition`). Extracted from
 * `TimelineActionTool.kt` in cycle 52
 * (`debt-split-timeline-action-tool`) to drop the dispatcher class
 * back under the R.5 #4 500-LOC default-P1 threshold after cycle 49's
 * transition absorption pushed it to 579 LOC. Same shape as
 * `TrackActionHandlers.kt` siblings — handlers are top-level
 * `internal suspend fun executeX(...)` and reach the package's small
 * shared helpers (`upsertTrackPreservingOrder` /
 * `emitTimelineSnapshot`) directly.
 *
 * **Axis**: this file grows with transition-specific verbs (future
 * `set_transition_duration` / `replace_transition_kind` etc). Adding
 * filter handlers (phase 1b) belongs in a separate `FilterAction
 * Handlers.kt` file rather than crowding here — the per-domain split
 * is what keeps the dispatcher class itself small.
 */

internal const val TRANSITION_ASSET_PREFIX = "transition:"

@OptIn(ExperimentalUuidApi::class)
internal suspend fun executeTransitionAdd(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    val items = input.transitionItems
        ?: error("action=add_transition requires `transitionItems` (omit `transitionClipIds`)")
    require(items.isNotEmpty()) { "transitionItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.durationSeconds > 0.0) {
            "transitionItems[$idx].durationSeconds must be > 0 (got ${item.durationSeconds})"
        }
    }
    val results = mutableListOf<TimelineActionTool.TransitionAddResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val from = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.fromClipId }?.let { track to it }
            } ?: error("transitionItems[$idx]: fromClipId ${item.fromClipId} not found")
            val to = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.toClipId }?.let { track to it }
            } ?: error("transitionItems[$idx]: toClipId ${item.toClipId} not found")
            val (fromTrack, fromClip) = from
            val (toTrack, toClip) = to
            if (fromTrack.id != toTrack.id) {
                error("transitionItems[$idx]: transition only supported between clips on the same track")
            }
            if (fromClip !is Clip.Video || toClip !is Clip.Video) {
                error("transitionItems[$idx]: transition only supports video clips")
            }
            if (fromClip.timeRange.end != toClip.timeRange.start) {
                error(
                    "transitionItems[$idx]: transition only supported between adjacent clips " +
                        "(from ends ${fromClip.timeRange.end}, to starts ${toClip.timeRange.start})",
                )
            }
            val duration = item.durationSeconds.seconds
            val midpoint = fromClip.timeRange.end - duration / 2
            val transitionRange = TimeRange(midpoint, duration)

            val transitionId = ClipId(Uuid.random().toString())
            val effectTrack = pickEffectTrack(tracks)
            val transitionClip = Clip.Video(
                id = transitionId,
                timeRange = transitionRange,
                sourceRange = TimeRange(Duration.ZERO, duration),
                assetId = AssetId("$TRANSITION_ASSET_PREFIX${item.transitionName}"),
                filters = listOf(
                    Filter(item.transitionName, mapOf("durationSeconds" to item.durationSeconds.toFloat())),
                ),
            )
            val newClips = (effectTrack.clips + transitionClip).sortedBy { it.timeRange.start }
            val newTrack = effectTrack.copy(clips = newClips)
            tracks = upsertTrackPreservingOrder(tracks, newTrack)

            results += TimelineActionTool.TransitionAddResult(
                transitionClipId = transitionId.value,
                trackId = newTrack.id.value,
                transitionName = item.transitionName,
                fromClipId = item.fromClipId,
                toClipId = item.toClipId,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "add ${results.size} transition(s)",
        outputForLlm = buildString {
            append("Added ${results.size} transition(s): ")
            append(results.joinToString(", ") { "${it.transitionName} between ${it.fromClipId}→${it.toClipId}" })
            append(". Timeline snapshot: ${snapshotId.value}")
        },
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "add_transition",
            snapshotId = snapshotId.value,
            addedTransitions = results,
        ),
    )
}

internal suspend fun executeTransitionRemove(
    store: ProjectStore,
    pid: ProjectId,
    input: TimelineActionTool.Input,
    ctx: ToolContext,
): ToolResult<TimelineActionTool.Output> {
    val ids = input.transitionClipIds
        ?: error("action=remove_transition requires `transitionClipIds` (omit `transitionItems`)")
    require(ids.isNotEmpty()) { "transitionClipIds must not be empty" }
    val results = mutableListOf<TimelineActionTool.TransitionRemoveResult>()
    var remainingTotal = 0

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        val touchedTrackIds = mutableSetOf<String>()
        ids.forEachIndexed { idx, transitionClipId ->
            val locatedTrack = tracks.firstOrNull { track ->
                track is Track.Effect && track.clips.any { it.id.value == transitionClipId }
            }
            val locatedClip = locatedTrack?.clips?.firstOrNull { it.id.value == transitionClipId }

            if (locatedTrack == null || locatedClip == null) {
                val elsewhere = tracks.firstOrNull { track ->
                    track !is Track.Effect && track.clips.any { it.id.value == transitionClipId }
                }
                if (elsewhere != null) {
                    error(
                        "transitionClipIds[$idx] ($transitionClipId) is on a ${trackKindOf(elsewhere)} " +
                            "track, not a transition. Use clip_action(action=remove) for regular clips.",
                    )
                }
                error("transitionClipIds[$idx] ($transitionClipId) not found in project ${pid.value}")
            }
            if (locatedClip !is Clip.Video || !locatedClip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)) {
                error(
                    "transitionClipIds[$idx] ($transitionClipId) is on the effect track but is not " +
                        "a transition (assetId '${(locatedClip as? Clip.Video)?.assetId?.value ?: "n/a"}' " +
                        "does not start with '$TRANSITION_ASSET_PREFIX'). Use clip_action(action=remove) if you meant " +
                        "a non-transition effect clip.",
                )
            }

            val transitionName = locatedClip.filters.firstOrNull()?.name
                ?: locatedClip.assetId.value.removePrefix(TRANSITION_ASSET_PREFIX)
            results += TimelineActionTool.TransitionRemoveResult(
                transitionClipId = transitionClipId,
                trackId = locatedTrack.id.value,
                transitionName = transitionName,
            )
            touchedTrackIds += locatedTrack.id.value

            val keep = locatedTrack.clips.filter { it.id.value != transitionClipId }
            val newTrack = (locatedTrack as Track.Effect).copy(clips = keep)
            tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
        }
        remainingTotal = tracks.filter { it.id.value in touchedTrackIds }.sumOf { track ->
            track.clips.count { clip ->
                clip is Clip.Video && clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)
            }
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    val summary = buildString {
        append("Removed ${results.size} transition(s)")
        if (results.isNotEmpty()) {
            val names = results.joinToString(", ") { "${it.transitionName} (${it.transitionClipId})" }
            append(": ").append(names)
        }
        append(". Timeline snapshot: ${snapshotId.value}")
    }
    return ToolResult(
        title = "remove ${results.size} transition(s)",
        outputForLlm = summary,
        data = TimelineActionTool.Output(
            projectId = pid.value,
            action = "remove_transition",
            snapshotId = snapshotId.value,
            removedTransitions = results,
            remainingTransitionsTotal = remainingTotal,
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun pickEffectTrack(tracks: List<Track>): Track.Effect {
    val match = tracks.firstOrNull { it is Track.Effect }
    return match as? Track.Effect ?: Track.Effect(TrackId(Uuid.random().toString()))
}

// trackKindOf reused from ClipActionHelpers.kt (already `internal` in this package).
