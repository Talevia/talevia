package io.talevia.core.tool.builtin.video

import io.talevia.core.JsonConfig
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Part
import io.talevia.core.tool.MediaAttachment
import io.talevia.core.tool.PathGuard
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.util.fnv1a64Hex
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Render the project's timeline to a file (VISION §2: "Artifact is a reproducible
 * deterministic product"). Before touching the engine we consult
 * [io.talevia.core.domain.Project.renderCache]: if the canonical `(timeline, output)`
 * fingerprint has been rendered before, we skip the render and return the cached
 * metadata. This is the coarse-grained first cut of incremental compilation — per-clip
 * re-render (the fine-grained cut) is an engine-layer follow-up.
 *
 * Cache key derivation: `fnv1a64Hex` over the canonical JSON of the timeline
 * concatenated with canonical `OutputSpec` fields. Because `Clip.sourceBinding` is
 * inside the timeline AND AIGC tools rewrite `assetId` on cache miss, any upstream
 * source change → distinct timeline JSON → distinct fingerprint. The DAG is respected
 * without a separate stale check at this layer.
 */
@OptIn(ExperimentalUuidApi::class)
class ExportTool(
    private val store: ProjectStore,
    private val engine: VideoEngine,
    private val clock: Clock = Clock.System,
) : Tool<ExportTool.Input, ExportTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val outputPath: String,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Int? = null,
        val videoCodec: String = "h264",
        val audioCodec: String = "aac",
        /** Bypass [io.talevia.core.domain.Project.renderCache]; always re-render. */
        val forceRender: Boolean = false,
    )
    @Serializable data class Output(
        val outputPath: String,
        val durationSeconds: Double,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        /** True when the render was skipped because of a [Project.renderCache] hit. */
        val cacheHit: Boolean = false,
    )

    override val id = "export"
    override val helpText =
        "Render the project's timeline to a media file at outputPath. Skips rendering when the timeline + " +
            "output spec hash matches a prior render (lockfile / DAG keeps this honest). " +
            "Streams progress as render-progress parts."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.export.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("outputPath") { put("type", "string"); put("description", "Absolute path where the rendered file should be written.") }
            putJsonObject("width") { put("type", "integer") }
            putJsonObject("height") { put("type", "integer") }
            putJsonObject("frameRate") { put("type", "integer") }
            putJsonObject("videoCodec") { put("type", "string") }
            putJsonObject("audioCodec") { put("type", "string") }
            putJsonObject("forceRender") { put("type", "boolean"); put("description", "Bypass the render cache and always re-render.") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("outputPath"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        PathGuard.validate(input.outputPath, requireAbsolute = true)
        val project = store.get(ProjectId(input.projectId)) ?: error("Project ${input.projectId} not found")
        val timeline = project.timeline
        val width = input.width ?: timeline.resolution.width
        val height = input.height ?: timeline.resolution.height
        val fps = input.frameRate ?: timeline.frameRate.numerator / timeline.frameRate.denominator

        val output = OutputSpec(
            targetPath = input.outputPath,
            resolution = Resolution(width, height),
            frameRate = fps,
            videoCodec = input.videoCodec,
            audioCodec = input.audioCodec,
        )

        val fingerprint = fingerprintOf(timeline, output)

        if (!input.forceRender) {
            val cached = project.renderCache.findByFingerprint(fingerprint)
            if (cached != null && cached.outputPath == input.outputPath) {
                val out = Output(
                    outputPath = cached.outputPath,
                    durationSeconds = cached.durationSeconds,
                    resolutionWidth = cached.resolutionWidth,
                    resolutionHeight = cached.resolutionHeight,
                    cacheHit = true,
                )
                return ToolResult(
                    title = "export (cached) → ${input.outputPath.substringAfterLast('/')}",
                    outputForLlm = "Render cache hit — reusing prior output at ${input.outputPath} " +
                        "(${cached.durationSeconds}s, ${cached.resolutionWidth}x${cached.resolutionHeight}).",
                    data = out,
                    attachments = listOf(
                        MediaAttachment(
                            mimeType = mimeTypeFor(input.outputPath),
                            source = input.outputPath,
                            widthPx = cached.resolutionWidth,
                            heightPx = cached.resolutionHeight,
                            durationMs = (cached.durationSeconds * 1000).toLong(),
                        ),
                    ),
                )
            }
        }

        val jobId = Uuid.random().toString()
        var failure: String? = null
        engine.render(timeline, output).collect { ev ->
            val partId = PartId(Uuid.random().toString())
            when (ev) {
                is RenderProgress.Started -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "started"),
                )
                is RenderProgress.Frames -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = ev.ratio, message = ev.message),
                )
                is RenderProgress.Completed -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 1f, message = "completed"),
                )
                is RenderProgress.Failed -> {
                    failure = ev.message
                    ctx.emitPart(
                        Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "failed: ${ev.message}"),
                    )
                }
            }
        }
        if (failure != null) error("export failed: $failure")

        val duration = timeline.duration.toDouble(DurationUnit.SECONDS)

        // Record the render in the cache BEFORE returning — next call with identical
        // inputs will hit. Uses ProjectStore.mutate so the append is serialized.
        store.mutate(ProjectId(input.projectId)) { p ->
            p.copy(
                renderCache = p.renderCache.append(
                    RenderCacheEntry(
                        fingerprint = fingerprint,
                        outputPath = input.outputPath,
                        resolutionWidth = width,
                        resolutionHeight = height,
                        durationSeconds = duration,
                        createdAtEpochMs = clock.now().toEpochMilliseconds(),
                    ),
                ),
            )
        }

        val out = Output(input.outputPath, duration, width, height, cacheHit = false)
        return ToolResult(
            title = "export → ${input.outputPath.substringAfterLast('/')}",
            outputForLlm = "Exported timeline (${duration}s, ${width}x${height}) to ${input.outputPath}",
            data = out,
            attachments = listOf(
                MediaAttachment(
                    mimeType = mimeTypeFor(input.outputPath),
                    source = input.outputPath,
                    widthPx = width,
                    heightPx = height,
                    durationMs = timeline.duration.toLong(DurationUnit.MILLISECONDS),
                ),
            ),
        )
    }

    private fun fingerprintOf(timeline: Timeline, output: OutputSpec): String {
        val json = JsonConfig.default
        val canonical = buildString {
            append(json.encodeToString(Timeline.serializer(), timeline))
            append('|')
            append("path=").append(output.targetPath)
            append("|res=").append(output.resolution.width).append('x').append(output.resolution.height)
            append("|fps=").append(output.frameRate)
            append("|vc=").append(output.videoCodec)
            append("|ac=").append(output.audioCodec)
        }
        return fnv1a64Hex(canonical)
    }

    /** MIME type from filename extension; falls back to a generic container. */
    internal fun mimeTypeFor(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
}
