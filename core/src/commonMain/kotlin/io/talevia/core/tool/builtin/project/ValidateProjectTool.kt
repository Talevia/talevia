package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Structural lint for a project. The agent can accumulate state across many
 * edits and — especially during autonomous multi-step runs — leave the
 * project in a shape that will bite at export time: a clip pointing at an
 * imported-then-deleted asset, a source binding whose node id no longer
 * exists after `source_node_action(action=remove)`, an audio clip whose `volume` was
 * clamped to 5.0 by a hand-authored snapshot, a fade-in/out pair that
 * exceeds the clip duration.
 *
 * Without a tool, the only way to catch these was to export and watch the
 * engine fail (or, worse, silently render a garbled frame). With this tool
 * the agent can run a cheap in-memory pass before every export, report
 * each issue with `severity / code / trackId / clipId`, and fix what it can
 * proactively.
 *
 * Intentionally narrow: structural invariants only. Does NOT cover:
 * - Staleness (lockfile / contentHash drift) — that's `find_stale_clips`.
 * - Render-cache health — exports already re-check.
 * - Semantic "is this a good edit" judgment — out of scope for a linter.
 *
 * The result is always returned (the tool never fails loudly for a
 * project-state problem); callers branch on `passed = errorCount == 0`.
 * A not-found project id still fails loudly because that's a caller bug.
 */
class ValidateProjectTool(
    private val projects: ProjectStore,
) : Tool<ValidateProjectTool.Input, ValidateProjectTool.Output> {

    @Serializable data class Input(
        val projectId: String,
    )

    @Serializable data class Issue(
        /** `"error"` | `"warn"` — `error` blocks export-readiness. */
        val severity: String,
        /**
         * Stable machine code so callers can switch on it (e.g. an autofix path
         * could target `"dangling-asset"` specifically). See the tool KDoc for
         * the current code vocabulary.
         */
        val code: String,
        val message: String,
        val trackId: String? = null,
        val clipId: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val issueCount: Int,
        val errorCount: Int,
        val warnCount: Int,
        val issues: List<Issue>,
        /** `true` iff `errorCount == 0`. Warnings do not block. */
        val passed: Boolean,
    )

    override val id: String = "validate_project"
    override val helpText: String =
        "Lint a project for structural invariants before export: dangling asset refs, " +
            "dangling source-node bindings, non-positive clip durations, audio volume / " +
            "fade envelope out of range, timeline duration out of sync with clips, and " +
            "source DAG integrity (parent refs pointing at missing nodes; parent cycles " +
            "that would break DFS traversal). Returns one row per issue; `passed=true` iff " +
            "no errors. Does NOT check content staleness — use `find_stale_clips` for that."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        val issues = computeIssues(project)
        val errorCount = issues.count { it.severity == "error" }
        val warnCount = issues.count { it.severity == "warn" }
        val out = Output(
            projectId = pid.value,
            issueCount = issues.size,
            errorCount = errorCount,
            warnCount = warnCount,
            issues = issues,
            passed = errorCount == 0,
        )
        return ToolResult(
            title = if (out.passed) "validate project: ok" else "validate project: $errorCount error(s)",
            outputForLlm = summarise(out),
            data = out,
        )
    }

    private fun summarise(out: Output): String {
        if (out.issues.isEmpty()) return "Project ${out.projectId} passed validation (0 issues)."
        val head = "Project ${out.projectId}: ${out.errorCount} error(s), ${out.warnCount} warning(s)."
        val body = out.issues.joinToString("\n") { issue ->
            val where = listOfNotNull(
                issue.trackId?.let { "track=$it" },
                issue.clipId?.let { "clip=$it" },
            ).joinToString(" ")
            val locator = if (where.isEmpty()) "" else " ($where)"
            "- [${issue.severity}] ${issue.code}$locator: ${issue.message}"
        }
        return "$head\n$body"
    }

    companion object {
        /**
         * Pure structural check over [project]. Shared by [ValidateProjectTool.execute]
         * (which has a stored project) and [ImportProjectFromJsonTool] (which has a
         * decoded-but-not-yet-upserted envelope). Extracted so the two code paths
         * cannot drift — an envelope that imports clean must also pass the linter on
         * the target, and vice versa.
         */
        fun computeIssues(project: Project): List<Issue> = buildList {
            addAll(timelineDurationIssues(project))
            for (track in project.timeline.tracks) {
                for (clip in track.clips) {
                    addAll(clipIssues(project, track, clip))
                }
            }
            addAll(sourceDagIssues(project))
        }

        /**
         * Render a short, human-readable summary of [issues] suitable for surfacing
         * in an `error { ... }` message at an import / ingest boundary. Caps at
         * [maxLines] issues and appends `... (N more)` for the rest.
         */
        fun renderIssues(issues: List<Issue>, maxLines: Int = 5): String {
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
    }
}
