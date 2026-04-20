package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
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
 * Mutate a project's [OutputProfile] after creation. Every editor lets the
 * user pick render settings per-project — resolution, frame rate, codec,
 * bitrate, container — and this is the tool that lets an agent do that when
 * the user changes their mind ("actually render this at 4K 60fps", "use
 * prores instead of h264 for the final").
 *
 * Patch semantics: every field is optional, null = keep, value = replace.
 * Caller must provide at least one field. The tool rejects bogus values at
 * the boundary (non-positive resolution, non-positive fps, non-positive
 * bitrates, blank codec/container strings).
 *
 * **Only `OutputProfile` is mutated, not [io.talevia.core.domain.Timeline].**
 * The timeline carries its own resolution/frame-rate as the **authoring**
 * canvas — content is edited inside that grid, so changing it mid-project
 * would reflow all time-based math (split points, transitions). The output
 * profile is the **render** spec: `ExportTool` consumes it to tell the
 * engine how to encode the final file. Changing output is safe and
 * idempotent (modulo re-render); changing authoring isn't. A future
 * `set_timeline_resolution` tool could handle the authoring case with
 * explicit reflow semantics — not this tool's job.
 *
 * Does NOT touch [io.talevia.core.domain.RenderCache] — if the new
 * profile differs from the previous one, the next export naturally
 * misses the cache (cache key includes the profile hash). No invalidation
 * step needed.
 */
class SetOutputProfileTool(
    private val projects: ProjectStore,
) : Tool<SetOutputProfileTool.Input, SetOutputProfileTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val resolutionWidth: Int? = null,
        val resolutionHeight: Int? = null,
        /** Integer frames per second — e.g. 24, 25, 30, 50, 60. NTSC 23.976/29.97 not yet exposed. */
        val fps: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val videoBitrate: Long? = null,
        val audioBitrate: Long? = null,
        val container: String? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val updatedFields: List<String>,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val fps: Int,
        val videoCodec: String,
        val audioCodec: String,
        val videoBitrate: Long,
        val audioBitrate: Long,
        val container: String,
    )

    override val id: String = "set_output_profile"
    override val helpText: String =
        "Patch a project's output profile (what ExportTool uses to render). Every field is optional; " +
            "unspecified fields keep their current value. resolutionWidth/resolutionHeight must be set " +
            "together if either is set. Does not change the timeline's authoring resolution — that's " +
            "separate from the render target. Use this when the user asks to render at a different " +
            "resolution, frame rate, codec, or bitrate."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("resolutionWidth") { put("type", "integer"); put("description", "Pixels. Pair with resolutionHeight.") }
            putJsonObject("resolutionHeight") { put("type", "integer"); put("description", "Pixels. Pair with resolutionWidth.") }
            putJsonObject("fps") {
                put("type", "integer")
                put("description", "Integer frames per second. Common: 24, 25, 30, 50, 60.")
            }
            putJsonObject("videoCodec") { put("type", "string"); put("description", "e.g. h264, h265, prores, vp9.") }
            putJsonObject("audioCodec") { put("type", "string"); put("description", "e.g. aac, opus, mp3.") }
            putJsonObject("videoBitrate") { put("type", "integer"); put("description", "Bits per second (e.g. 8000000 for 8 Mbps).") }
            putJsonObject("audioBitrate") { put("type", "integer"); put("description", "Bits per second (e.g. 192000 for 192 kbps).") }
            putJsonObject("container") { put("type", "string"); put("description", "e.g. mp4, mov, mkv, webm.") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)

        val hasAnyEdit = listOfNotNull(
            input.resolutionWidth, input.resolutionHeight, input.fps,
            input.videoCodec, input.audioCodec,
            input.videoBitrate, input.audioBitrate, input.container,
        ).isNotEmpty()
        require(hasAnyEdit) { "at least one field must be provided" }

        val widthProvided = input.resolutionWidth != null
        val heightProvided = input.resolutionHeight != null
        require(widthProvided == heightProvided) {
            "resolutionWidth and resolutionHeight must be provided together (got width=${input.resolutionWidth}, height=${input.resolutionHeight})"
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
            ?: error("project ${input.projectId} not found")

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

        val fpsOut = if (updated.frameRate.denominator == 1) updated.frameRate.numerator
        else updated.frameRate.numerator / updated.frameRate.denominator
        val out = Output(
            projectId = pid.value,
            updatedFields = changed,
            resolutionWidth = updated.resolution.width,
            resolutionHeight = updated.resolution.height,
            fps = fpsOut,
            videoCodec = updated.videoCodec,
            audioCodec = updated.audioCodec,
            videoBitrate = updated.videoBitrate,
            audioBitrate = updated.audioBitrate,
            container = updated.container,
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
            data = out,
        )
    }
}
