package io.talevia.core.tool.builtin.video

import io.talevia.core.JsonConfig
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.domain.render.transitionFadesPerClip
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.staleClipsFromLockfile
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
 * deterministic product").
 *
 * Stale-guard (VISION §3.2). Before touching the engine we compute
 * `staleClipsFromLockfile` — clips whose conditioning source nodes drifted since
 * the asset was generated. If any are stale the export is refused with an
 * actionable message pointing at `find_stale_clips` / regeneration, unless the
 * caller opts in with `allowStale=true`. Without this guard the render cache
 * would happily hand back output whose visual content no longer matches the
 * current source graph: the cache is keyed on timeline JSON + output spec, and
 * a source-only edit doesn't change either (the drift shows up only via
 * `clip.sourceBinding` → lockfile hash comparison).
 *
 * Render cache (VISION §3.2, coarse cut). After the stale-guard clears, we
 * consult [io.talevia.core.domain.Project.renderCache]: if the canonical
 * `(timeline, output)` fingerprint has been rendered before, we skip the render
 * and return the cached metadata. Per-clip re-render (the fine-grained cut) is
 * an engine-layer follow-up.
 *
 * Cache key derivation: `fnv1a64Hex` over the canonical JSON of the timeline
 * concatenated with canonical `OutputSpec` fields. AIGC regeneration produces a
 * new assetId on the timeline, so fresh regenerations invalidate the cache
 * naturally; the stale-guard above catches the orthogonal case where source
 * drifted but the agent hasn't regenerated yet.
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
        /**
         * Opt-in override when the project has clips whose conditioning source nodes
         * have drifted since generation (VISION §3.2). Default: refuse to export so
         * the user sees stale content explicitly via `find_stale_clips` and decides
         * whether to regenerate or ship-as-is.
         */
        val allowStale: Boolean = false,
    )
    @Serializable data class Output(
        val outputPath: String,
        val durationSeconds: Double,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        /** True when the render was skipped because of a [Project.renderCache] hit. */
        val cacheHit: Boolean = false,
        /**
         * Stale clip ids included in the render because [Input.allowStale] was set.
         * Empty on the normal fresh path and when the tool refused to render.
         */
        val staleClipsIncluded: List<String> = emptyList(),
        /**
         * When the engine's per-clip mezzanine path ran (VISION §3.2 fine cut):
         * how many clips reused a cached mezzanine vs how many were re-rendered.
         * Both zero on the whole-timeline path (engine didn't opt in, or the
         * timeline shape fell back to full render).
         */
        val perClipCacheHits: Int = 0,
        val perClipCacheMisses: Int = 0,
    )

    override val id = "export"
    override val helpText =
        "Render the project's timeline to a media file at outputPath. Skips rendering when the timeline + " +
            "output spec hash matches a prior render. Refuses to export when any AIGC clip is stale " +
            "(source nodes drifted since generation) unless allowStale=true. " +
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
            putJsonObject("allowStale") {
                put("type", "boolean")
                put(
                    "description",
                    "Export even if some AIGC clips are stale (their conditioning source nodes drifted since " +
                        "generation). Default false — call find_stale_clips first and regenerate before exporting.",
                )
            }
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

        val staleReports = project.staleClipsFromLockfile()
        if (staleReports.isNotEmpty() && !input.allowStale) {
            val head = staleReports.take(5).joinToString("; ") { r ->
                "${r.clipId.value} (drifted: ${r.changedSourceIds.joinToString(",") { it.value }})"
            }
            val tail = if (staleReports.size > 5) "; …" else ""
            error(
                "export refused: ${staleReports.size} stale clip(s). " +
                    "Conditioning source nodes drifted since the asset was generated — " +
                    "run find_stale_clips, regenerate the affected clips, then retry. " +
                    "Pass allowStale=true to export as-is. Stale: $head$tail",
            )
        }

        val staleClipIds = staleReports.map { it.clipId.value }
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
                    staleClipsIncluded = staleClipIds,
                )
                val staleSuffix = if (staleClipIds.isEmpty()) "" else " [allowStale: ${staleClipIds.size} stale clip(s)]"
                return ToolResult(
                    title = "export (cached) → ${input.outputPath.substringAfterLast('/')}",
                    outputForLlm = "Render cache hit — reusing prior output at ${input.outputPath} " +
                        "(${cached.durationSeconds}s, ${cached.resolutionWidth}x${cached.resolutionHeight}).$staleSuffix",
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

        val perClipShape = timelineFitsPerClipPath(timeline)
        val perClipStats = if (engine.supportsPerClipCache && perClipShape != null) {
            runPerClipRender(project, perClipShape, output, input, ctx)
        } else {
            runWholeTimelineRender(timeline, output, ctx)
            PerClipStats(hits = 0, misses = 0)
        }

        val duration = timeline.duration.toDouble(DurationUnit.SECONDS)

        // Record the whole-timeline render in the cache BEFORE returning — next call
        // with identical inputs short-circuits before even reaching the per-clip path.
        // Uses ProjectStore.mutate so the append is serialized.
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

        val out = Output(
            outputPath = input.outputPath,
            durationSeconds = duration,
            resolutionWidth = width,
            resolutionHeight = height,
            cacheHit = false,
            staleClipsIncluded = staleClipIds,
            perClipCacheHits = perClipStats.hits,
            perClipCacheMisses = perClipStats.misses,
        )
        val staleSuffix = if (staleClipIds.isEmpty()) "" else " [allowStale: ${staleClipIds.size} stale clip(s)]"
        val perClipSuffix = if (perClipStats.hits + perClipStats.misses == 0) {
            ""
        } else {
            " [per-clip cache: ${perClipStats.hits} hit, ${perClipStats.misses} miss]"
        }
        return ToolResult(
            title = "export → ${input.outputPath.substringAfterLast('/')}",
            outputForLlm = "Exported timeline (${duration}s, ${width}x${height}) to ${input.outputPath}.$staleSuffix$perClipSuffix",
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

    private data class PerClipStats(val hits: Int, val misses: Int)

    /**
     * Shape the per-clip path expects: a single [Track.Video] with at least one
     * [Clip.Video], plus the subtitle clips the concat step needs to bake back in.
     * Any other shape (multi-video, audio/effect-only, empty) forces the fall-back
     * to whole-timeline render — matching the current engine's whole-timeline scope.
     */
    private data class PerClipShape(
        val videoClips: List<Clip.Video>,
        val subtitles: List<Clip.Text>,
    )

    private fun timelineFitsPerClipPath(timeline: Timeline): PerClipShape? {
        val videoTracks = timeline.tracks.filterIsInstance<Track.Video>()
        if (videoTracks.size != 1) return null
        val videoClips = videoTracks[0].clips.filterIsInstance<Clip.Video>()
        if (videoClips.isEmpty()) return null
        // If the video track holds non-Video clips (shouldn't happen today, but
        // defensively) the whole-timeline path's `filterIsInstance<Clip.Video>()`
        // would drop them too, so we'd quietly differ; fall back instead.
        if (videoClips.size != videoTracks[0].clips.size) return null
        val subtitles = timeline.tracks
            .filterIsInstance<Track.Subtitle>()
            .flatMap { it.clips.filterIsInstance<Clip.Text>() }
        return PerClipShape(videoClips = videoClips, subtitles = subtitles)
    }

    /**
     * Whole-timeline render path (the original flow). Collects progress from the
     * engine's [VideoEngine.render] and forwards as [Part.RenderProgress] events.
     */
    private suspend fun runWholeTimelineRender(
        timeline: Timeline,
        output: OutputSpec,
        ctx: ToolContext,
    ) {
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
    }

    /**
     * Per-clip mezzanine path (VISION §3.2 fine cut). For each video clip, compute
     * a fingerprint over (clip JSON + neighbour transition fades + bound-source
     * deep hashes + output profile essentials); reuse a cached mezzanine when the
     * fingerprint matches AND the mezzanine file is still on disk, otherwise ask
     * the engine to render a fresh one. Stitch them with [VideoEngine.concatMezzanines],
     * which applies any timeline subtitles post-concat (subtitles are timeline-relative
     * and deliberately not baked into mezzanines so a subtitle-only edit only
     * re-runs the cheap concat step).
     *
     * Mezzanines live under `<outputDir>/.talevia-render-cache/<projectId>/<fingerprint>.mp4`
     * so they stay local to the user's export target but can be shared across
     * multiple exports of the same project to the same directory.
     *
     * Progress is emitted at the granularity of "clip N of M" events (each clip
     * contributes one [RenderProgress.Frames]) plus a final concat progress line —
     * richer per-frame progress is available on the whole-timeline path via
     * ffmpeg's `-progress` pipe; the per-clip path optimises for compile time,
     * not for frame-level UI animation.
     */
    private suspend fun runPerClipRender(
        project: Project,
        shape: PerClipShape,
        output: OutputSpec,
        input: Input,
        ctx: ToolContext,
    ): PerClipStats {
        val jobId = Uuid.random().toString()
        val projectId = project.id
        val mezzanineDir = mezzanineDirFor(input.outputPath, projectId.value)

        // Pre-compute fade map + per-binding deep source hashes once for the
        // whole timeline. deepContentHashOf caches intermediate results so shared
        // ancestors (style_bible parents several character_refs) only walk once.
        val fadesByClipId = project.timeline.transitionFadesPerClip(shape.videoClips)
        val deepHashCache = mutableMapOf<io.talevia.core.SourceNodeId, String>()

        ctx.emitPart(
            Part.RenderProgress(
                PartId(Uuid.random().toString()), ctx.messageId, ctx.sessionId, clock.now(),
                jobId = jobId, ratio = 0f,
                message = "per-clip render: ${shape.videoClips.size} clip(s)",
            ),
        )

        var hits = 0
        var misses = 0
        val mezzaninePaths = mutableListOf<String>()
        val newCacheEntries = mutableListOf<ClipRenderCacheEntry>()

        for ((idx, clip) in shape.videoClips.withIndex()) {
            val fades = fadesByClipId[clip.id]
            val boundHashes = clip.sourceBinding
                .filter { it in project.source.byId }
                .associateWith { project.source.deepContentHashOf(it, deepHashCache) }
            val fingerprint = clipMezzanineFingerprint(
                clip = clip,
                fades = fades,
                boundSourceDeepHashes = boundHashes,
                output = output,
            )
            val cached = project.clipRenderCache.findByFingerprint(fingerprint)
            val cachedPath = cached?.mezzaninePath
            val cacheHitValid = cachedPath != null && engine.mezzaninePresent(cachedPath)
            val chosenPath: String
            if (cacheHitValid) {
                hits += 1
                chosenPath = cachedPath!!
            } else {
                misses += 1
                val mezzaninePath = "$mezzanineDir/$fingerprint.mp4"
                engine.renderClip(
                    clip = clip,
                    fades = fades,
                    output = output,
                    mezzaninePath = mezzaninePath,
                )
                chosenPath = mezzaninePath
                newCacheEntries += ClipRenderCacheEntry(
                    fingerprint = fingerprint,
                    mezzaninePath = mezzaninePath,
                    resolutionWidth = output.resolution.width,
                    resolutionHeight = output.resolution.height,
                    durationSeconds = clip.sourceRange.duration.toDouble(DurationUnit.SECONDS),
                    createdAtEpochMs = clock.now().toEpochMilliseconds(),
                )
            }
            mezzaninePaths += chosenPath
            val stageRatio = (idx + 1).toFloat() / (shape.videoClips.size + 1).toFloat()
            ctx.emitPart(
                Part.RenderProgress(
                    PartId(Uuid.random().toString()), ctx.messageId, ctx.sessionId, clock.now(),
                    jobId = jobId, ratio = stageRatio,
                    message = "clip ${idx + 1}/${shape.videoClips.size} " +
                        (if (cacheHitValid) "cached" else "rendered"),
                ),
            )
        }

        engine.concatMezzanines(
            mezzaninePaths = mezzaninePaths,
            subtitles = shape.subtitles,
            output = output,
        )
        ctx.emitPart(
            Part.RenderProgress(
                PartId(Uuid.random().toString()), ctx.messageId, ctx.sessionId, clock.now(),
                jobId = jobId, ratio = 1f, message = "completed",
            ),
        )

        if (newCacheEntries.isNotEmpty()) {
            store.mutate(projectId) { p ->
                var cache = p.clipRenderCache
                for (entry in newCacheEntries) cache = cache.append(entry)
                p.copy(clipRenderCache = cache)
            }
        }

        return PerClipStats(hits = hits, misses = misses)
    }

    private fun mezzanineDirFor(outputPath: String, projectId: String): String {
        val parent = outputPath.substringBeforeLast('/', missingDelimiterValue = ".")
        // Percent-encode enough of projectId so a path-unfriendly id (colon on
        // Windows, or the default Uuid.toString format on JVM) doesn't break the
        // resulting directory. We only need to survive `/` and `\` here.
        val safeProjectId = projectId.replace('/', '_').replace('\\', '_')
        return "$parent/.talevia-render-cache/$safeProjectId"
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
