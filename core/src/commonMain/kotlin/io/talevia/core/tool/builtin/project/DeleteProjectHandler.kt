package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `project_action(action="delete")` handler — drop a project from the
 * catalog (and, with `deleteFiles=true`, the on-disk bundle). Behaviour
 * preserved from the legacy `DeleteProjectTool`: fail loud on missing
 * id; default keeps the bundle on disk so an accidental delete never
 * destroys user-authored files.
 */
internal suspend fun executeDeleteProject(
    projects: ProjectStore,
    input: ProjectActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectActionTool.Output> {
    val rawId = input.projectId
        ?: error("action=delete requires `projectId`")
    val pid = ProjectId(rawId)
    val meta = projects.summary(pid)
        ?: error("Project $rawId not found")
    val onDiskPath = projects.pathOf(pid)?.toString()
    projects.delete(pid, deleteFiles = input.deleteFiles)

    val data = ProjectActionTool.Output(
        projectId = pid.value,
        action = "delete",
        deleteResult = ProjectActionTool.DeleteResult(
            title = meta.title,
            filesDeleted = input.deleteFiles && onDiskPath != null,
            path = onDiskPath,
        ),
    )
    val filesNote = if (input.deleteFiles && onDiskPath != null) {
        " — on-disk bundle at $onDiskPath was also removed"
    } else {
        ""
    }
    return ToolResult(
        title = "delete project ${meta.title}",
        outputForLlm = "Deleted project ${pid.value} (\"${meta.title}\")$filesNote. " +
            "Sessions that reference this project are now orphaned.",
        data = data,
    )
}
