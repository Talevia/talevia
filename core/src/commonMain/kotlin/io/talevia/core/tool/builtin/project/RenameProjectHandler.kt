package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `project_action(action="rename")` handler — change a project's
 * human-readable title. Behaviour preserved from the legacy
 * `RenameProjectTool`: projectId never changes; non-destructive (no
 * timeline / asset / source / lockfile mutation); a no-op if the title
 * is already what's asked for so the agent doesn't have to filter
 * "is-this-a-real-change" itself.
 */
internal suspend fun executeRenameProject(
    projects: ProjectStore,
    input: ProjectActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectActionTool.Output> {
    val rawId = input.projectId
        ?: error("action=rename requires `projectId`")
    val newTitle = input.title
        ?: error("action=rename requires `title`")
    require(newTitle.isNotBlank()) { "title must not be blank" }
    val pid = ProjectId(rawId)
    val before = projects.summary(pid)
        ?: error("Project $rawId not found")

    if (before.title == newTitle) {
        return ToolResult(
            title = "rename project ${pid.value}",
            outputForLlm = "Project ${pid.value} already titled \"$newTitle\"; no change.",
            data = ProjectActionTool.Output(
                projectId = pid.value,
                action = "rename",
                renameResult = ProjectActionTool.RenameResult(
                    previousTitle = before.title,
                    title = before.title,
                ),
            ),
        )
    }

    projects.setTitle(pid, newTitle)
    val data = ProjectActionTool.Output(
        projectId = pid.value,
        action = "rename",
        renameResult = ProjectActionTool.RenameResult(
            previousTitle = before.title,
            title = newTitle,
        ),
    )
    return ToolResult(
        title = "rename project ${pid.value}",
        outputForLlm = "Renamed project ${pid.value} from \"${before.title}\" to \"$newTitle\".",
        data = data,
    )
}
