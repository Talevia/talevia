package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `project_action(kind="lifecycle", args={action="set_output_profile"})` handler — patch a
 * project's [io.talevia.core.domain.OutputProfile]. Behaviour preserved
 * from the legacy `SetOutputProfileTool`:
 *
 * - At least one profile field required; resolutionWidth /
 *   resolutionHeight must come paired.
 * - All numeric fields must be `> 0`; codec / container strings must
 *   be non-blank.
 * - **Only `OutputProfile` mutates** — the timeline's authoring
 *   resolution is intentionally untouched (different concern: output
 *   = render spec, timeline = authoring grid).
 * - Reports `updatedFields` even when an explicit value matches the
 *   current — empty list signals "nothing actually changed".
 */
internal suspend fun executeSetOutputProfile(
    projects: ProjectStore,
    input: ProjectLifecycleActionTool.Input,
    @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
): ToolResult<ProjectLifecycleActionTool.Output> {
    val rawId = input.projectId
        ?: error("action=set_output_profile requires `projectId`")
    val pid = ProjectId(rawId)

    val hasAnyEdit = listOfNotNull(
        input.resolutionWidth, input.resolutionHeight, input.fps,
        input.videoCodec, input.audioCodec,
        input.videoBitrate, input.audioBitrate, input.container,
    ).isNotEmpty()
    require(hasAnyEdit) { "at least one field must be provided" }

    val widthProvided = input.resolutionWidth != null
    val heightProvided = input.resolutionHeight != null
    require(widthProvided == heightProvided) {
        "resolutionWidth and resolutionHeight must be provided together " +
            "(got width=${input.resolutionWidth}, height=${input.resolutionHeight})"
    }
    if (widthProvided) {
        require(input.resolutionWidth!! > 0) { "resolutionWidth must be > 0 (got ${input.resolutionWidth})" }
        require(input.resolutionHeight!! > 0) { "resolutionHeight must be > 0 (got ${input.resolutionHeight})" }
    }
    input.fps?.let { require(it > 0) { "fps must be > 0 (got $it)" } }
    input.videoBitrate?.let { require(it > 0) { "videoBitrate must be > 0 (got $it)" } }
    input.audioBitrate?.let { require(it > 0) { "audioBitrate must be > 0 (got $it)" } }
    input.videoCodec?.let { require(it.isNotBlank()) { "videoCodec must not be blank" } }
    input.audioCodec?.let { require(it.isNotBlank()) { "audioCodec must not be blank" } }
    input.container?.let { require(it.isNotBlank()) { "container must not be blank" } }

    val current = projects.get(pid)?.outputProfile
        ?: error("project $rawId not found")

    val updated = current.copy(
        resolution = if (widthProvided) Resolution(input.resolutionWidth!!, input.resolutionHeight!!) else current.resolution,
        frameRate = input.fps?.let { FrameRate(it, 1) } ?: current.frameRate,
        videoCodec = input.videoCodec ?: current.videoCodec,
        audioCodec = input.audioCodec ?: current.audioCodec,
        videoBitrate = input.videoBitrate ?: current.videoBitrate,
        audioBitrate = input.audioBitrate ?: current.audioBitrate,
        container = input.container ?: current.container,
    )

    val changed = buildList {
        if (widthProvided && updated.resolution != current.resolution) add("resolution")
        if (input.fps != null && updated.frameRate != current.frameRate) add("frameRate")
        if (input.videoCodec != null && updated.videoCodec != current.videoCodec) add("videoCodec")
        if (input.audioCodec != null && updated.audioCodec != current.audioCodec) add("audioCodec")
        if (input.videoBitrate != null && updated.videoBitrate != current.videoBitrate) add("videoBitrate")
        if (input.audioBitrate != null && updated.audioBitrate != current.audioBitrate) add("audioBitrate")
        if (input.container != null && updated.container != current.container) add("container")
    }

    projects.mutate(pid) { p -> p.copy(outputProfile = updated) }

    val fpsOut = if (updated.frameRate.denominator == 1) {
        updated.frameRate.numerator
    } else {
        updated.frameRate.numerator / updated.frameRate.denominator
    }
    val data = ProjectLifecycleActionTool.Output(
        projectId = pid.value,
        action = "set_output_profile",
        setOutputProfileResult = ProjectLifecycleActionTool.SetOutputProfileResult(
            updatedFields = changed,
            resolutionWidth = updated.resolution.width,
            resolutionHeight = updated.resolution.height,
            fps = fpsOut,
            videoCodec = updated.videoCodec,
            audioCodec = updated.audioCodec,
            videoBitrate = updated.videoBitrate,
            audioBitrate = updated.audioBitrate,
            container = updated.container,
        ),
    )
    val summary = if (changed.isEmpty()) {
        "Output profile for project ${pid.value} unchanged (all provided values matched current)."
    } else {
        "Updated ${changed.joinToString(", ")} on project ${pid.value}. " +
            "Render spec is now ${updated.resolution.width}x${updated.resolution.height}@${fpsOut}fps " +
            "${updated.videoCodec}/${updated.audioCodec} in .${updated.container}."
    }
    return ToolResult(
        title = "set output profile ${pid.value}",
        outputForLlm = summary,
        data = data,
    )
}
