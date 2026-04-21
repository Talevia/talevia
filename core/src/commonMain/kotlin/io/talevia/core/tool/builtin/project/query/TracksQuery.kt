package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=tracks` — one row per timeline track with kind, clip count, span.
 *
 * Carved out of `ProjectQueryTool.runTracks` when that file crossed the
 * 500-line long-file threshold (decision
 * `docs/decisions/2026-04-21-debt-split-projectquerytool.md`). Behavior is
 * identical to the old private method.
 */
internal fun runTracksQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val kindFilter = input.trackKind?.trim()?.lowercase()
    if (kindFilter != null && kindFilter !in VALID_TRACK_KINDS) {
        error("trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')")
    }
    val sortBy = input.sortBy?.trim()?.lowercase()
    if (sortBy != null && sortBy !in TRACK_SORTS) {
        error("sortBy for select=tracks must be one of ${TRACK_SORTS.joinToString(", ")} (got '${input.sortBy}')")
    }

    val filtered = project.timeline.tracks.withIndex().mapNotNull { (index, track) ->
        val kind = trackKindOf(track)
        if (kindFilter != null && kind != kindFilter) return@mapNotNull null
        if (input.onlyNonEmpty == true && track.clips.isEmpty()) return@mapNotNull null
        buildTrackRow(track, index, kind)
    }

    val sorted = when (sortBy) {
        null, "index" -> filtered
        "clipcount" -> filtered.sortedByDescending { it.clipCount }
        "span" -> filtered.sortedByDescending { it.spanSeconds ?: 0.0 }
        else -> error("unreachable")
    }

    val page = sorted.drop(offset).take(limit)
    val rows = encodeRows(ListSerializer(ProjectQueryTool.TrackRow.serializer()), page)
    val llmBody = when {
        page.isEmpty() -> "No tracks match the given filter."
        else -> page.joinToString("\n") { r ->
            val span = if (r.isEmpty) {
                "empty"
            } else {
                "${r.clipCount} clips, ${r.firstClipStartSeconds}s..${r.lastClipEndSeconds}s"
            }
            "- #${r.index} [${r.trackKind}/${r.trackId}] $span"
        }
    }
    val scope = buildList {
        kindFilter?.let { add("kind=$it") }
        if (input.onlyNonEmpty == true) add("non-empty")
        sortBy?.let { add("sort=$it") }
    }.joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }
    return ToolResult(
        title = "project_query tracks (${page.size}/${filtered.size})",
        outputForLlm = "Project ${project.id.value}: ${page.size}/${filtered.size} tracks$scope.\n$llmBody",
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_TRACKS,
            total = filtered.size,
            returned = page.size,
            rows = rows,
        ),
    )
}

private fun buildTrackRow(track: Track, index: Int, kind: String): ProjectQueryTool.TrackRow {
    val clips = track.clips
    if (clips.isEmpty()) {
        return ProjectQueryTool.TrackRow(
            trackId = track.id.value,
            trackKind = kind,
            index = index,
            clipCount = 0,
            isEmpty = true,
        )
    }
    val firstStart = clips.minOf { it.timeRange.start }
    val lastEnd = clips.maxOf { it.timeRange.end }
    return ProjectQueryTool.TrackRow(
        trackId = track.id.value,
        trackKind = kind,
        index = index,
        clipCount = clips.size,
        isEmpty = false,
        firstClipStartSeconds = firstStart.toSecondsDouble(),
        lastClipEndSeconds = lastEnd.toSecondsDouble(),
        spanSeconds = (lastEnd - firstStart).toSecondsDouble(),
    )
}
