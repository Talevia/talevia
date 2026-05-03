package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import okio.Path.Companion.toPath

/**
 * `project_action(kind="lifecycle", args={action="create"})` handler — bootstrap a fresh project
 * with empty timeline / assets / source DAG. Behaviour preserved from
 * the legacy `CreateProjectTool`:
 *
 * - `title` required + non-blank.
 * - `resolutionPreset` and `fps` parse via the same accept-list as
 *   before (720p / 1080p / 4k; 24 / 30 / 60).
 * - When `path` is provided, hand off to `ProjectStore.createAt` so the
 *   bundle materialises at exactly that location; the store mints the
 *   id. When `path` is null, slug the title (or use `projectId` if
 *   given), fail loud on duplicate, then upsert.
 */
internal suspend fun executeCreateProject(
    projects: ProjectStore,
    sessions: SessionStore?,
    clock: Clock,
    input: ProjectLifecycleActionTool.Input,
    ctx: ToolContext,
): ToolResult<ProjectLifecycleActionTool.Output> {
    val title = input.title?.takeIf { it.isNotBlank() }
        ?: error("action=create requires non-blank `title`")
    val resolution = parseCreateResolution(input.resolutionPreset)
    val frameRate = parseCreateFrameRate(input.fps)
    val profile = OutputProfile(resolution = resolution, frameRate = frameRate)
    val timeline = Timeline(resolution = resolution, frameRate = frameRate)

    val pid: ProjectId = if (!input.path.isNullOrBlank()) {
        val created = projects.createAt(
            path = input.path.toPath(),
            title = title,
            timeline = timeline,
            outputProfile = profile,
        )
        created.id
    } else {
        val rawId = resolveDefaultHomeProjectId(input.projectId, title)
        val candidate = ProjectId(rawId)
        require(projects.get(candidate) == null) {
            "project ${candidate.value} already exists; use list_projects to find an unused id or operate on the existing one"
        }
        val project = Project(id = candidate, timeline = timeline, outputProfile = profile)
        projects.upsert(title, project)
        candidate
    }

    autoBindSessionToProject(sessions, clock, ctx, pid)

    val data = ProjectLifecycleActionTool.Output(
        projectId = pid.value,
        action = "create",
        createResult = ProjectLifecycleActionTool.CreateResult(
            title = title,
            resolutionWidth = resolution.width,
            resolutionHeight = resolution.height,
            fps = frameRate.numerator,
        ),
    )
    val pathNote = input.path?.let { " at $it" }.orEmpty()
    val bindNote = if (sessions != null) " Session ${ctx.sessionId.value} now bound to it." else ""
    return ToolResult(
        title = "create project $title",
        outputForLlm = "Created project ${pid.value} (\"$title\")$pathNote at " +
            "${resolution.width}x${resolution.height}@${frameRate.numerator}fps.$bindNote " +
            "Pass projectId=${pid.value} to subsequent tool calls.",
        data = data,
    )
}

private fun parseCreateResolution(preset: String?): Resolution = when (preset?.lowercase()) {
    null, "", "1080p" -> Resolution(1920, 1080)
    "720p" -> Resolution(1280, 720)
    "4k", "2160p" -> Resolution(3840, 2160)
    else -> throw IllegalArgumentException(
        "unknown resolutionPreset '$preset'; accepted: 720p, 1080p, 4k",
    )
}

private fun parseCreateFrameRate(fps: Int?): FrameRate = when (fps) {
    null, 30 -> FrameRate.FPS_30
    24 -> FrameRate.FPS_24
    60 -> FrameRate.FPS_60
    else -> throw IllegalArgumentException("unsupported fps=$fps; accepted: 24, 30, 60")
}
