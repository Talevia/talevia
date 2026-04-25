package io.talevia.core.tool.builtin.video

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.render.ProvenanceManifest
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.MediaAttachment
import io.talevia.core.tool.PathGuard
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.export.PerClipStats
import io.talevia.core.tool.builtin.video.export.runPerClipRender
import io.talevia.core.tool.builtin.video.export.runWholeTimelineRender
import io.talevia.core.tool.builtin.video.export.timelineFitsPerClipPath
import io.talevia.core.util.fnv1a64Hex
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
        /**
         * Per-clip USD-cents attribution: `clipId.value` →
         * `lockfile.byAssetId[clip.assetId].costCents` (or null when the
         * clip's asset is non-AIGC / never-priced). Empty when the
         * exported timeline has no clips with priced provenance — the
         * common case for renders of pure user footage with no AIGC
         * inserts.
         *
         * The attribution is exact for AIGC clips: the lockfile entry
         * keyed by the clip's assetId is the call that produced the
         * file, so its `costCents` is the cost of THIS clip's asset
         * (cache hits include their original price too — billing
         * doesn't refund). Clips that share an asset with other clips
         * report the same cents on every clip; the sum is intentional
         * because each clip references the same paid output.
         */
        val perClipCostCents: Map<String, Long?> = emptyMap(),
        /**
         * Sum of non-null entries in [perClipCostCents]. Convenience
         * roll-up so the common "what did this export cost" question
         * answers without a client-side reduce. Zero when no clip has
         * priced provenance.
         */
        val totalCostCents: Long = 0L,
        /**
         * Provenance record baked into the exported file's container metadata
         * (VISION §5.3). Null only on engines that can't write container
         * metadata (Media3 / AVFoundation today) — the FFmpeg engine always
         * stamps one. Tools downstream (e.g. "which Project produced this
         * mp4?") can read it back via `probe(source).comment` +
         * `ProvenanceManifest.decodeFromComment(...)`.
         */
        val provenance: ProvenanceManifest? = null,
    )

    override val id = "export"
    override val helpText =
        "Render the project's timeline to a media file at outputPath. Skips rendering when the timeline + " +
            "output spec hash matches a prior render. Refuses to export when any AIGC clip is stale " +
            "(source nodes drifted since generation) unless allowStale=true. " +
            "Bakes a provenance manifest (projectId + timelineHash + lockfileHash) into the output " +
            "container's comment tag — read back via probe / ProvenanceManifest.decodeFromComment. " +
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

        // Provenance manifest (VISION §5.3). Pure function of (projectId,
        // timeline, lockfile) — no timestamps / random ids — so two identical
        // exports bake byte-identical metadata, preserving the
        // ExportDeterminismTest bit-exact contract.
        val provenance = provenanceOf(project)
        val output = OutputSpec(
            targetPath = input.outputPath,
            resolution = Resolution(width, height),
            frameRate = fps,
            videoCodec = input.videoCodec,
            audioCodec = input.audioCodec,
            metadata = mapOf("comment" to provenance.encodeToComment()),
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
                val (cachedPerClipCost, cachedTotalCost) = buildPerClipCostAttribution(project)
                val out = Output(
                    outputPath = cached.outputPath,
                    durationSeconds = cached.durationSeconds,
                    resolutionWidth = cached.resolutionWidth,
                    resolutionHeight = cached.resolutionHeight,
                    cacheHit = true,
                    staleClipsIncluded = staleClipIds,
                    perClipCostCents = cachedPerClipCost,
                    totalCostCents = cachedTotalCost,
                    provenance = provenance,
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

        // Build a per-export resolver bound to this project's bundle. AIGC and
        // imported-into-bundle assets resolve relative to bundleRoot; external
        // source files (MediaSource.File) keep their absolute paths.
        val resolver = store.pathOf(project.id)?.let {
            io.talevia.core.platform.BundleMediaPathResolver(project, it)
        }

        val perClipShape = timelineFitsPerClipPath(timeline)
        val perClipStats = if (engine.supportsPerClipCache && perClipShape != null) {
            runPerClipRender(engine, store, project, perClipShape, output, input.outputPath, ctx, clock, resolver)
        } else {
            runWholeTimelineRender(engine, timeline, output, ctx, clock, resolver)
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

        // Re-read the project so attribution sees any lockfile entries that
        // landed during this export (e.g. AIGC tools the timeline references).
        val finalProject = store.get(project.id) ?: project
        val (perClipCost, totalCost) = buildPerClipCostAttribution(finalProject)
        val out = Output(
            outputPath = input.outputPath,
            durationSeconds = duration,
            resolutionWidth = width,
            resolutionHeight = height,
            cacheHit = false,
            staleClipsIncluded = staleClipIds,
            perClipCacheHits = perClipStats.hits,
            perClipCacheMisses = perClipStats.misses,
            perClipCostCents = perClipCost,
            totalCostCents = totalCost,
            provenance = provenance,
        )
        val staleSuffix = if (staleClipIds.isEmpty()) "" else " [allowStale: ${staleClipIds.size} stale clip(s)]"
        val perClipSuffix = if (perClipStats.hits + perClipStats.misses == 0) {
            ""
        } else {
            " [per-clip cache: ${perClipStats.hits} hit, ${perClipStats.misses} miss]"
        }
        val costSuffix = if (totalCost > 0L) " [cost: ${totalCost}¢]" else ""
        return ToolResult(
            title = "export → ${input.outputPath.substringAfterLast('/')}",
            outputForLlm = "Exported timeline (${duration}s, ${width}x${height}) to " +
                "${input.outputPath}.$staleSuffix$perClipSuffix$costSuffix",
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

    /**
     * Build the [ProvenanceManifest] for [project]. Two hashes:
     *  - `timelineHash` over the canonical Timeline JSON alone (no output
     *    spec, no lockfile) — a Timeline edit flips it, nothing else.
     *  - `lockfileHash` over the canonical `Lockfile` JSON — new AIGC
     *    generation or pin/unpin flips it.
     *
     * Both via `JsonConfig.default` so bit-exact re-exports produce
     * bit-exact hashes (ExportDeterminismTest relies on this).
     */
    private fun provenanceOf(project: Project): ProvenanceManifest {
        val json = JsonConfig.default
        val timelineHash = fnv1a64Hex(json.encodeToString(Timeline.serializer(), project.timeline))
        val lockfileHash = fnv1a64Hex(json.encodeToString(Lockfile.serializer(), project.lockfile))
        return ProvenanceManifest(
            projectId = project.id.value,
            timelineHash = timelineHash,
            lockfileHash = lockfileHash,
        )
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

    /**
     * Build the per-clip cost map for [Output.perClipCostCents] +
     * [Output.totalCostCents].
     *
     * Lookup is via `lockfile.byAssetId[clip.assetId]` — exact
     * one-to-one keying. A clip whose asset wasn't AIGC-produced has
     * no lockfile entry → null cents (still in the map; the agent can
     * see "this clip is unpriced" distinct from "0 cents"). Clips
     * sharing an asset report the same cents per clip; the sum is
     * intentional because each clip references the same paid output.
     *
     * Returns the (map, total) pair so the caller doesn't have to
     * reduce twice. Empty inputs and clips without `assetId` (text
     * clips) are skipped — they'd never have a lockfile entry anyway.
     */
    private fun buildPerClipCostAttribution(project: Project): Pair<Map<String, Long?>, Long> {
        val perClip = mutableMapOf<String, Long?>()
        var total = 0L
        for (track in project.timeline.tracks) {
            for (clip in track.clips) {
                val assetId = when (clip) {
                    is io.talevia.core.domain.Clip.Video -> clip.assetId
                    is io.talevia.core.domain.Clip.Audio -> clip.assetId
                    is io.talevia.core.domain.Clip.Text -> null
                }
                val cents = assetId?.let { project.lockfile.byAssetId[it]?.costCents }
                perClip[clip.id.value] = cents
                if (cents != null) total += cents
            }
        }
        return perClip to total
    }
}
