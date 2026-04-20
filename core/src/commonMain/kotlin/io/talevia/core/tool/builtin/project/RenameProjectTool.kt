package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
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
 * Rename a project's human-readable title. The `projectId` never changes — only the
 * catalog `title` column, which `list_projects` surfaces. Safe / non-destructive:
 * the underlying [io.talevia.core.domain.Project] model (timeline, assets, source,
 * lockfile, snapshots) is not touched. Fork + delete already give the agent a way
 * to re-title a project by cloning, but that duplicates every asset reference and
 * breaks identity; this tool is the in-place alternative.
 */
class RenameProjectTool(
    private val projects: ProjectStore,
) : Tool<RenameProjectTool.Input, RenameProjectTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val title: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val previousTitle: String,
        val title: String,
    )

    override val id: String = "rename_project"
    override val helpText: String =
        "Change a project's human-readable title. projectId is unchanged; the title is the label " +
            "shown by list_projects and get_project_state. Non-destructive — no timeline, asset, " +
            "source, lockfile, or snapshot data is modified."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("title") {
                put("type", "string")
                put("description", "New human-readable title. Must not be blank.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("title"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        val pid = ProjectId(input.projectId)
        val before = projects.summary(pid)
            ?: error("Project ${input.projectId} not found")
        if (before.title == input.title) {
            return ToolResult(
                title = "rename project ${pid.value}",
                outputForLlm = "Project ${pid.value} already titled \"${input.title}\"; no change.",
                data = Output(pid.value, before.title, before.title),
            )
        }
        projects.setTitle(pid, input.title)
        val out = Output(projectId = pid.value, previousTitle = before.title, title = input.title)
        return ToolResult(
            title = "rename project ${pid.value}",
            outputForLlm = "Renamed project ${pid.value} from \"${before.title}\" to \"${input.title}\".",
            data = out,
        )
    }
}
