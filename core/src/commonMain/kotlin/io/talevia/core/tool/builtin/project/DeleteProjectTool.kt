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

    @Serializable data class Input(
        val projectId: String,
        /**
         * When `true` (and the underlying [ProjectStore] is file-backed),
         * the project's on-disk bundle (talevia.json, .gitignore, media/,
         * .talevia-cache/) is removed in addition to unregistering from the
         * recents list. When `false` (default) only the registry entry is
         * removed; the bundle stays put on disk and can be re-opened later
         * via `open_project`.
         *
         * Default `false` so an accidental `delete_project` never destroys
         * user-authored files.
         */
        val deleteFiles: Boolean = false,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        /** True when on-disk files were also removed. */
        val filesDeleted: Boolean = false,
        /** Path that was (or would have been) deleted, when known. */
        val path: String? = null,
    )

    override val id: String = "delete_project"
    override val helpText: String =
        "Permanently delete a project's catalog metadata. Irreversible — the user is asked to " +
            "confirm. Sessions that reference the project are not deleted; warn the user if any " +
            "look orphaned. Pass deleteFiles=true to ALSO remove the on-disk bundle " +
            "(talevia.json, media/, .talevia-cache/) — the user is told the path that will be " +
            "wiped before they confirm. Default false leaves files on disk, just unregisters " +
            "the project from the recents list (re-openable via open_project)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.destructive")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("deleteFiles") {
                put("type", "boolean")
                put(
                    "description",
                    "When true, also delete the on-disk bundle. Default false: only " +
                        "unregister from the recents list.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val meta = projects.summary(pid)
            ?: error("Project ${input.projectId} not found")
        val onDiskPath = projects.pathOf(pid)?.toString()
        projects.delete(pid, deleteFiles = input.deleteFiles)
        val out = Output(
            projectId = pid.value,
            title = meta.title,
            filesDeleted = input.deleteFiles && onDiskPath != null,
            path = onDiskPath,
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
            data = out,
        )
    }
}
