package io.talevia.core.tool.builtin.project

import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import okio.Path.Companion.toPath

/**
 * `project_action(action="open")` handler — register an existing
 * Talevia bundle on this machine. Behaviour preserved from the legacy
 * `OpenProjectTool`: requires a non-blank absolute path to a directory
 * containing `talevia.json`; the store loads the project (refreshing
 * the recents catalog as a side effect); we then pull the title from
 * the catalog summary so the output looks like `create`'s.
 */
internal suspend fun executeOpenProject(
    projects: ProjectStore,
    input: ProjectActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectActionTool.Output> {
    val rawPath = input.path
        ?: error("action=open requires `path`")
    require(rawPath.isNotBlank()) { "open: path must not be blank" }
    val project = projects.openAt(rawPath.toPath())
    val title = projects.summary(project.id)?.title ?: project.id.value

    val data = ProjectActionTool.Output(
        projectId = project.id.value,
        action = "open",
        openResult = ProjectActionTool.OpenResult(title = title),
    )
    return ToolResult(
        title = "open project $title",
        outputForLlm = "Opened project ${project.id.value} (\"$title\") at $rawPath. " +
            "Registered in the recents list — pass projectId=${project.id.value} to subsequent " +
            "tool calls or call switch_project to bind it to the session.",
        data = data,
    )
}
