package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `select=transitions` — one row per transition on the timeline, with
 * flanking video clip ids recovered by midpoint-matching (within one
 * frame at 30fps). Replaces the pre-consolidation `list_transitions`
 * tool. Filter: [ProjectQueryTool.Input.onlyOrphaned].
 */
internal fun runTransitionsQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val videoTracks = project.timeline.tracks.filterIsInstance<Track.Video>()
    val effectTracks = project.timeline.tracks.filterIsInstance<Track.Effect>()

    val rows = effectTracks.flatMap { track ->
        track.clips.mapNotNull { clip ->
            if (clip !is Clip.Video) return@mapNotNull null
            if (!clip.assetId.value.startsWith(TRANSITION_ASSET_PREFIX)) return@mapNotNull null
            val name = clip.filters.firstOrNull()?.name
                ?: clip.assetId.value.removePrefix(TRANSITION_ASSET_PREFIX)
            val start = clip.timeRange.start
            val end = clip.timeRange.end
            val midpoint = start + (end - start) / 2
            val fromClip = videoTracks.findClipEndingNear(midpoint)
            val toClip = videoTracks.findClipStartingNear(midpoint)
            val orphaned = fromClip == null && toClip == null
            ProjectQueryTool.TransitionRow(
                transitionClipId = clip.id.value,
                trackId = track.id.value,
                transitionName = name,
                startSeconds = start.toSecondsDouble(),
                durationSeconds = clip.timeRange.duration.toSecondsDouble(),
                endSeconds = end.toSecondsDouble(),
                fromClipId = fromClip?.id?.value,
                toClipId = toClip?.id?.value,
                orphaned = orphaned,
            )
        }
    }.sortedBy { it.startSeconds }

    val filtered = if (input.onlyOrphaned == true) rows.filter { it.orphaned } else rows
    val page = filtered.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(ProjectQueryTool.TransitionRow.serializer()), page)

    val orphanCount = rows.count { it.orphaned }
    val body = if (page.isEmpty()) {
        if (rows.isEmpty()) "No transitions on this timeline." else "No transitions matched the filter."
    } else {
        page.joinToString("\n") { r ->
            val pair = when {
                r.orphaned -> "orphaned"
                r.fromClipId != null && r.toClipId != null -> "${r.fromClipId} → ${r.toClipId}"
                r.fromClipId != null -> "${r.fromClipId} → (missing)"
                r.toClipId != null -> "(missing) → ${r.toClipId}"
                else -> "?"
            }
            "- ${r.transitionName} ${r.transitionClipId} @ ${r.startSeconds}s +${r.durationSeconds}s [$pair]"
        }
    }
    val scopeSuffix = if (input.onlyOrphaned == true) " (onlyOrphaned)" else ""
    return ToolResult(
        title = "project_query transitions (${page.size}/${filtered.size})",
        outputForLlm = "Project ${project.id.value}: ${page.size} returned of ${rows.size} total " +
            "($orphanCount orphaned)$scopeSuffix.\n$body",
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_TRANSITIONS,
            total = filtered.size,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}

private fun List<Track.Video>.findClipEndingNear(midpoint: Duration): Clip.Video? =
    asSequence()
        .flatMap { it.clips.asSequence() }
        .filterIsInstance<Clip.Video>()
        .filter { !it.assetId.value.startsWith(TRANSITION_ASSET_PREFIX) }
        .firstOrNull { (it.timeRange.end - midpoint).absoluteValue <= EPSILON }

private fun List<Track.Video>.findClipStartingNear(midpoint: Duration): Clip.Video? =
    asSequence()
        .flatMap { it.clips.asSequence() }
        .filterIsInstance<Clip.Video>()
        .filter { !it.assetId.value.startsWith(TRANSITION_ASSET_PREFIX) }
        .firstOrNull { (it.timeRange.start - midpoint).absoluteValue <= EPSILON }

private const val TRANSITION_ASSET_PREFIX = "transition:"

/** One frame at 30fps ≈ 33ms; 34ms covers rounding slop from AddTransition. */
private val EPSILON: Duration = 34.milliseconds
