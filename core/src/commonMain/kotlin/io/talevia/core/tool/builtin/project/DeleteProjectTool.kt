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
 * Drop a project + every Source / Timeline / Lockfile / RenderCache row attached
 * to it. Permission `project.destructive` defaults to ASK because the loss is
 * unrecoverable from the user's perspective — there's no undo lane below the
 * store. Sessions that referenced the project are not auto-pruned; the agent
 * should warn the user when relevant.
 */
class DeleteProjectTool(
    private val projects: ProjectStore,
) : Tool<DeleteProjectTool.Input, DeleteProjectTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class Output(
        val projectId: String,
        val title: String,
    )

    override val id: String = "delete_project"
    override val helpText: String =
        "Permanently delete a project and all of its source / timeline / asset metadata. " +
            "Irreversible — the user is asked to confirm. Sessions that reference the project " +
            "are not deleted; warn the user if any look orphaned."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

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
        val meta = projects.summary(pid)
            ?: error("Project ${input.projectId} not found")
        projects.delete(pid)
        val out = Output(projectId = pid.value, title = meta.title)
        return ToolResult(
            title = "delete project ${meta.title}",
            outputForLlm = "Deleted project ${pid.value} (\"${meta.title}\"). " +
                "Sessions that reference this project are now orphaned.",
            data = out,
        )
    }
}
