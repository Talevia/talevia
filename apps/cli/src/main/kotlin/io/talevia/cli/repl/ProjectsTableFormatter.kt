package io.talevia.cli.repl

import io.talevia.core.domain.ProjectSummary
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Render `/projects` output — a table of every project this CLI's
 * `ProjectStore.listSummaries()` knows about, sorted updated-desc
 * (most recent first). Three columns: id, title, path (when known
 * from `RecentsRegistry`). Sorting matches `ListProjectsTool`'s
 * default `sortBy = "updated-desc"` so the agent + the operator see
 * the same ordering for the same data.
 *
 * Path comes through a lookup callback rather than a field on
 * `ProjectSummary` because the registry-side path resolution is
 * machine-local (one bundle on disk can be at different paths
 * across machines). The dispatcher passes
 * `id -> projects.pathOf(ProjectId(id))?.toString()`; tests can
 * substitute a deterministic map.
 *
 * Empty list prints an actionable hint pointing at `/new` /
 * `create_project` so an operator who runs `/projects` early
 * doesn't see a blank screen.
 */
internal fun formatProjectsTable(
    summaries: List<ProjectSummary>,
    pathLookup: (String) -> String?,
): String {
    if (summaries.isEmpty()) {
        return Styles.meta(
            "no projects in the recents registry yet — `talevia new <path>` (or " +
                "`create_project` from inside the agent) bootstraps one.",
        )
    }
    val sorted = summaries.sortedByDescending { it.updatedAtEpochMs }
    val idWidth = sorted.maxOf { it.id.length }
    val titleWidth = sorted.maxOf { (it.title.takeIf(String::isNotBlank) ?: "(untitled)").length }
        .coerceAtMost(TITLE_DISPLAY_CHARS)
    val header = "${Styles.accent("projects")} ${sorted.size} project(s) (most-recently-updated first)"
    return buildString {
        appendLine(header)
        for (s in sorted) {
            val time = formatLocalTime(s.updatedAtEpochMs)
            val title = (s.title.takeIf(String::isNotBlank) ?: "(untitled)").take(TITLE_DISPLAY_CHARS)
            val path = pathLookup(s.id) ?: "—"
            appendLine(
                "  ${Styles.meta(time)}  ${Styles.accent(s.id.padEnd(idWidth))}  ${title.padEnd(titleWidth)}  ${Styles.meta(path)}",
            )
        }
    }.trimEnd()
}

private const val TITLE_DISPLAY_CHARS = 60

private fun formatLocalTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val date = "${local.year}-${pad2(local.monthNumber)}-${pad2(local.dayOfMonth)}"
    val time = "${pad2(local.hour)}:${pad2(local.minute)}"
    return "$date $time"
}

private fun pad2(n: Int): String = n.toString().padStart(2, '0')
