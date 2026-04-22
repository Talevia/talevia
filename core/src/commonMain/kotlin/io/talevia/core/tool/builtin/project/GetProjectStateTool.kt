package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
 * Single-project orientation snapshot — what the LLM needs to decide what to do
 * next without dragging the whole Project JSON into context.
 *
 * Reports: title, output profile, asset count, source-DAG size + revision, lockfile
 * + render-cache sizes, timeline track count + duration. The agent can quote these
 * back when planning ("project has 12 source nodes; 2 character_refs already exist —
 * I'll reuse them").
 */
class GetProjectStateTool(
    private val projects: ProjectStore,
) : Tool<GetProjectStateTool.Input, GetProjectStateTool.Output> {

    @Serializable data class Input(val projectId: String)

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val assetCount: Int,
        val sourceNodeCount: Int,
        val sourceRevision: Long,
        val lockfileEntryCount: Int,
        val renderCacheEntryCount: Int,
        val trackCount: Int,
        val timelineDurationSeconds: Double,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
    )

    override val id: String = "get_project_state"
    override val helpText: String =
        "Snapshot of one project: title, output profile, counts (assets, source nodes, lockfile, " +
            "render cache, tracks), timeline duration, and timestamps. Use after list_projects to " +
            "decide which project to operate on, or before planning multi-step edits."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

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
        val meta = projects.summary(pid)
            ?: error("Project ${input.projectId} has no catalog row — store inconsistency")
        val resolution = project.outputProfile.resolution
        val out = Output(
            projectId = pid.value,
            title = meta.title,
            resolutionWidth = resolution.width,
            resolutionHeight = resolution.height,
            fps = project.outputProfile.frameRate.numerator,
            assetCount = project.assets.size,
            sourceNodeCount = project.source.nodes.size,
            sourceRevision = project.source.revision,
            lockfileEntryCount = project.lockfile.entries.size,
            renderCacheEntryCount = project.renderCache.entries.size,
            trackCount = project.timeline.tracks.size,
            timelineDurationSeconds = project.timeline.duration.inWholeMilliseconds / 1000.0,
            createdAtEpochMs = meta.createdAtEpochMs,
            updatedAtEpochMs = meta.updatedAtEpochMs,
        )
        return ToolResult(
            title = "get project ${meta.title}",
            outputForLlm = "Project ${pid.value} (\"${meta.title}\"): " +
                "${resolution.width}x${resolution.height}@${out.fps}fps, " +
                "${out.assetCount} asset(s), ${out.sourceNodeCount} source node(s) at rev ${out.sourceRevision}, " +
                "${out.lockfileEntryCount} lockfile entr(ies), ${out.renderCacheEntryCount} render-cache entr(ies), " +
                "${out.trackCount} track(s), timeline duration ${out.timelineDurationSeconds}s.",
            data = out,
        )
    }
}
