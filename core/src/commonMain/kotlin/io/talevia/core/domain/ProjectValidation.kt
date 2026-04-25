package io.talevia.core.domain

import io.talevia.core.tool.builtin.project.clipIssues
import io.talevia.core.tool.builtin.project.sourceDagIssues
import io.talevia.core.tool.builtin.project.timelineDurationIssues
import kotlinx.serialization.Serializable

/**
 * One structural lint finding on a [Project].
 *
 * Pre-cycle-138 this lived as `ValidateProjectTool.Issue`; cycle 139 lifted
 * it to a top-level type when the standalone `validate_project` tool was
 * folded into `project_query(select=validation)`. Both the new query select
 * and `ImportProjectFromJsonTool`'s envelope-time check consume this type,
 * so it now sits in the domain layer (next to the data it describes)
 * rather than under any single tool.
 *
 * `code` is a stable machine-readable token — autofix paths can switch on
 * `"dangling-asset"`, `"dangling-source-binding"`, `"non-positive-duration"`,
 * etc. `severity` is `"error"` (blocks export-readiness) or `"warn"` (does
 * not block but worth surfacing).
 */
@Serializable
data class ValidationIssue(
    val severity: String,
    val code: String,
    val message: String,
    val trackId: String? = null,
    val clipId: String? = null,
)

/**
 * Pure structural check over [project]. Composes per-axis helpers from
 * `core/tool/builtin/project/ValidateProjectChecks.kt` so the same rule
 * vocabulary fires on a stored project (via
 * `project_query(select=validation)`) and on a decoded-but-not-yet-
 * upserted envelope (via `ImportProjectFromJsonTool`). Keeping the two
 * call sites pinned to one function prevents the import path and the
 * linter from drifting — an envelope that imports clean must also pass
 * the linter on the target, and vice versa.
 */
fun computeProjectValidationIssues(project: Project): List<ValidationIssue> = buildList {
    addAll(timelineDurationIssues(project))
    for (track in project.timeline.tracks) {
        for (clip in track.clips) {
            addAll(clipIssues(project, track, clip))
        }
    }
    addAll(sourceDagIssues(project))
}

/**
 * Render a short, human-readable summary of [issues] suitable for
 * surfacing in an `error { ... }` message at an import / ingest
 * boundary. Caps at [maxLines] issues and appends `… (N more)` for
 * the rest.
 */
fun renderProjectValidationIssues(
    issues: List<ValidationIssue>,
    maxLines: Int = 5,
): String {
    if (issues.isEmpty()) return ""
    val head = issues.take(maxLines).joinToString("\n") { issue ->
        val where = listOfNotNull(
            issue.trackId?.let { "track=$it" },
            issue.clipId?.let { "clip=$it" },
        ).joinToString(" ")
        val locator = if (where.isEmpty()) "" else " ($where)"
        "- [${issue.severity}] ${issue.code}$locator: ${issue.message}"
    }
    val extra = issues.size - maxLines
    return if (extra > 0) "$head\n… ($extra more)" else head
}
