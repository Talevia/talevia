package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.domain.ValidationIssue
import io.talevia.core.domain.computeProjectValidationIssues
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=validation` — structural lint over a project. Replaces the
 * standalone `validate_project` tool (cycle 139 fold). Mirrors the
 * earlier folds of `find_stale_clips` → `select=stale_clips`,
 * `describe_clip` → `select=clip`, `describe_lockfile_entry` →
 * `select=lockfile_entry`. Single-purpose read-only checks belong on
 * the unified dispatcher rather than each carrying their own Tool
 * spec into every LLM turn.
 *
 * Returns one row per issue — directly the [ValidationIssue] type that
 * lives in `core.domain` (also consumed by `ImportProjectFromJsonTool`
 * at envelope-ingest time, so envelope-import must pass the same
 * checks). The dispatcher's uniform `total` field carries the issue
 * count; `passed = (total == 0)` is what callers branch on.
 *
 * Three axes of checks (preserved verbatim from the pre-fold tool):
 * timeline duration consistency, per-clip integrity (asset /
 * source-binding / volume / fade), and source-DAG integrity (dangling
 * parents, parent cycles). Does **not** cover content staleness — use
 * `project_query(select=stale_clips)` for that. Does not cover
 * render-cache health (export already re-checks).
 */
internal fun runValidationQuery(
    project: Project,
): ToolResult<ProjectQueryTool.Output> {
    val issues = computeProjectValidationIssues(project)
    val errorCount = issues.count { it.severity == "error" }
    val warnCount = issues.count { it.severity == "warn" }
    val passed = errorCount == 0
    val jsonRows = encodeRows(ListSerializer(ValidationIssue.serializer()), issues)

    val title = if (passed) "project_query validation: ok" else "project_query validation: $errorCount error(s)"
    val outputForLlm = if (issues.isEmpty()) {
        "Project ${project.id.value} passed validation (0 issues)."
    } else {
        val head = "Project ${project.id.value}: $errorCount error(s), $warnCount warning(s)."
        val body = issues.joinToString("\n") { issue ->
            val where = listOfNotNull(
                issue.trackId?.let { "track=$it" },
                issue.clipId?.let { "clip=$it" },
            ).joinToString(" ")
            val locator = if (where.isEmpty()) "" else " ($where)"
            "- [${issue.severity}] ${issue.code}$locator: ${issue.message}"
        }
        "$head\n$body"
    }
    return ToolResult(
        title = title,
        outputForLlm = outputForLlm,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_VALIDATION,
            total = issues.size,
            returned = issues.size,
            rows = jsonRows,
        ),
    )
}
