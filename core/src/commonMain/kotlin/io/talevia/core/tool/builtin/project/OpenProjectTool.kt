package io.talevia.core.tool.builtin.project

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
import okio.Path.Companion.toPath

/**
 * Open an existing Talevia project bundle at a filesystem path. Registers
 * the project in the local recents list so it shows up in `list_projects`
 * and can be referenced by id from other tools afterwards.
 *
 * Use this whenever the user gives you a path on disk to a Talevia project
 * — for example one cloned from git, copied from another machine, or
 * unzipped from a backup. The path must be a directory containing
 * `talevia.json`. Loading is read-only: nothing on disk is modified.
 *
 * SQL-backed stores do not support [ProjectStore.openAt] and will surface
 * an `UnsupportedOperationException`; the JVM apps wire the file-backed
 * store as the default.
 *
 * Permission: `"project.read"` — defaults to ASK so a user with strict
 * permissions sees the prompt before another machine's project is
 * registered locally.
 */
class OpenProjectTool(
    private val projects: ProjectStore,
) : Tool<OpenProjectTool.Input, OpenProjectTool.Output> {

    @Serializable data class Input(
        /**
         * Absolute filesystem path of the project bundle directory. Must
         * contain a `talevia.json` file at the root. Path must be
         * non-blank.
         */
        val path: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
    )

    override val id: String = "open_project"
    override val helpText: String =
        "Open an existing Talevia project bundle at the given filesystem path. Registers the " +
            "project in the local recents list so it shows up in `list_projects`. The path must " +
            "be a directory containing `talevia.json`. Use this when the user gives you a path " +
            "on disk to a Talevia project (e.g. one cloned from git or copied from another " +
            "machine)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put(
                    "description",
                    "Absolute filesystem path of the bundle directory. Must contain talevia.json at the root.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.path.isNotBlank()) { "open_project: path must not be blank" }
        val project = projects.openAt(input.path.toPath())
        // Title isn't on Project; pull it from the catalog summary the
        // openAt call just refreshed.
        val title = projects.summary(project.id)?.title ?: project.id.value
        val out = Output(projectId = project.id.value, title = title)
        return ToolResult(
            title = "open project ${title}",
            outputForLlm = "Opened project ${project.id.value} (\"$title\") at ${input.path}. " +
                "Registered in the recents list — pass projectId=${project.id.value} to subsequent " +
                "tool calls or call switch_project to bind it to the session.",
            data = out,
        )
    }
}
