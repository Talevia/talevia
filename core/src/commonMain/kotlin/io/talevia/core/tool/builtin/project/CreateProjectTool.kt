package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
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
 * Bootstrap a fresh project (VISION §5.2 — agent as a project manager).
 *
 * Empty timeline + assets + source DAG; `OutputProfile` defaults to 1080p/30 unless
 * the LLM picks a preset. Fails loud on duplicate `projectId` so the agent has to
 * reconcile (most likely by calling `list_projects` first) rather than silently
 * stomping a project the user already cares about.
 */
class CreateProjectTool(
    private val projects: ProjectStore,
) : Tool<CreateProjectTool.Input, CreateProjectTool.Output> {

    @Serializable data class Input(
        val title: String,
        val projectId: String? = null,
        val resolutionPreset: String? = null,
        val fps: Int? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val title: String,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
    )

    override val id: String = "create_project"
    override val helpText: String =
        "Create a new project (empty timeline / assets / source). Returns the projectId — pass it to " +
            "every subsequent tool call. resolutionPreset accepts 720p / 1080p / 4k; fps accepts 24 / 30 / 60. " +
            "Defaults: 1080p / 30. Fails loud if a project with the same id already exists."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "string"); put("description", "Human-readable project title.") }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Optional explicit id; defaults to a slugged variant of title.")
            }
            putJsonObject("resolutionPreset") {
                put("type", "string")
                put("description", "Output resolution: 720p, 1080p (default), or 4k.")
            }
            putJsonObject("fps") {
                put("type", "integer")
                put("description", "Output frame rate: 24, 30 (default), or 60.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("title"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.title.isNotBlank()) { "title must not be blank" }
        val rawId = input.projectId?.takeIf { it.isNotBlank() } ?: slugifyProjectId(input.title)
        val pid = ProjectId(rawId)
        require(projects.get(pid) == null) {
            "project ${pid.value} already exists; use list_projects to find an unused id or operate on the existing one"
        }
        val resolution = parseResolution(input.resolutionPreset)
        val frameRate = parseFrameRate(input.fps)
        val profile = OutputProfile(resolution = resolution, frameRate = frameRate)
        val timeline = Timeline(resolution = resolution, frameRate = frameRate)
        val project = Project(id = pid, timeline = timeline, outputProfile = profile)
        projects.upsert(input.title, project)
        val out = Output(
            projectId = pid.value,
            title = input.title,
            resolutionWidth = resolution.width,
            resolutionHeight = resolution.height,
            fps = frameRate.numerator,
        )
        return ToolResult(
            title = "create project ${input.title}",
            outputForLlm = "Created project ${pid.value} (\"${input.title}\") at " +
                "${resolution.width}x${resolution.height}@${frameRate.numerator}fps. " +
                "Pass projectId=${pid.value} to subsequent tool calls.",
            data = out,
        )
    }

    private fun parseResolution(preset: String?): Resolution = when (preset?.lowercase()) {
        null, "", "1080p" -> Resolution(1920, 1080)
        "720p" -> Resolution(1280, 720)
        "4k", "2160p" -> Resolution(3840, 2160)
        else -> throw IllegalArgumentException(
            "unknown resolutionPreset '$preset'; accepted: 720p, 1080p, 4k",
        )
    }

    private fun parseFrameRate(fps: Int?): FrameRate = when (fps) {
        null, 30 -> FrameRate.FPS_30
        24 -> FrameRate.FPS_24
        60 -> FrameRate.FPS_60
        else -> throw IllegalArgumentException("unsupported fps=$fps; accepted: 24, 30, 60")
    }
}
