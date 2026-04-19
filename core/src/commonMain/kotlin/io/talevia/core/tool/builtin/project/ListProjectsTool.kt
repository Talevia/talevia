package io.talevia.core.tool.builtin.project

import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.ProjectSummary
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Catalog read for orientation. Returns lightweight metadata only — no Source DAG
 * or Timeline JSON decode. Pair with `get_project_state` for a single-project deep
 * dive once the LLM has picked which one to operate on.
 */
class ListProjectsTool(
    private val projects: ProjectStore,
) : Tool<ListProjectsTool.Input, ListProjectsTool.Output> {

    @Serializable class Input

    @Serializable data class Output(
        val totalCount: Int,
        val projects: List<ProjectSummary>,
    )

    override val id: String = "list_projects"
    override val helpText: String =
        "List every project in the store with id / title / created+updated timestamps. " +
            "Use this for orientation; call get_project_state for full per-project details."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val summaries = projects.listSummaries()
        val out = Output(totalCount = summaries.size, projects = summaries)
        val tail = if (summaries.isEmpty()) "no projects yet — call create_project to bootstrap one"
        else summaries.joinToString("\n") { "- ${it.id} (\"${it.title}\")" }
        return ToolResult(
            title = "list projects (${summaries.size})",
            outputForLlm = "${summaries.size} project(s):\n$tail",
            data = out,
        )
    }
}
