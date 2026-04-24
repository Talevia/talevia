package io.talevia.core.tool.builtin.video.export

import io.talevia.core.PartId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.domain.render.transitionFadesPerClip
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.datetime.Clock
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class PerClipStats(val hits: Int, val misses: Int)

/**
 * Shape the per-clip path expects: a single [Track.Video] with at least one
 * [Clip.Video], plus the subtitle clips the concat step needs to bake back in.
 * Any other shape (multi-video, audio/effect-only, empty) forces the fall-back
 * to whole-timeline render — matching the current engine's whole-timeline scope.
 */
internal data class PerClipShape(
    val videoClips: List<Clip.Video>,
    val subtitles: List<Clip.Text>,
)

internal fun timelineFitsPerClipPath(timeline: Timeline): PerClipShape? {
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

internal fun mezzanineDirFor(outputPath: String, projectId: String): String {
    val parent = outputPath.substringBeforeLast('/', missingDelimiterValue = ".")
    // Percent-encode enough of projectId so a path-unfriendly id (colon on
    // Windows, or the default Uuid.toString format on JVM) doesn't break the
    // resulting directory. We only need to survive `/` and `\` here.
    val safeProjectId = projectId.replace('/', '_').replace('\\', '_')
    return "$parent/.talevia-render-cache/$safeProjectId"
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
 * contributes one [io.talevia.core.platform.RenderProgress.Frames]) plus a
 * final concat progress line — richer per-frame progress is available on the
 * whole-timeline path via ffmpeg's `-progress` pipe; the per-clip path
 * optimises for compile time, not for frame-level UI animation.
 *
 * Extracted from `ExportTool` in the `debt-split-export-tool` cycle; see
 * `docs/decisions/2026-04-22-debt-split-export-tool.md`.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun runPerClipRender(
    engine: VideoEngine,
    store: ProjectStore,
    project: Project,
    shape: PerClipShape,
    output: OutputSpec,
    outputPath: String,
    ctx: ToolContext,
    clock: Clock,
    resolver: io.talevia.core.platform.MediaPathResolver? = null,
): PerClipStats {
    val jobId = Uuid.random().toString()
    val projectId = project.id
    val mezzanineDir = mezzanineDirFor(outputPath, projectId.value)

    // Pre-compute fade map + per-binding deep source hashes once for the
    // whole timeline. deepContentHashOf caches intermediate results so shared
    // ancestors (style_bible parents several character_refs) only walk once.
    val fadesByClipId = project.timeline.transitionFadesPerClip(shape.videoClips)
    val deepHashCache = mutableMapOf<SourceNodeId, String>()

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
    // Mezzanines produced this run but NOT yet persisted to clipRenderCache.
    // If anything between renderClip() and the final store.mutate() throws
    // (ffmpeg crash, subtitle overlay failure, coroutine cancel), these files
    // are orphaned on disk — the cache has no record of them so GC won't
    // find them and the fingerprint won't recur exactly (source drift is
    // part of the key). The try/catch below walks this list and deletes each
    // on failure so a mid-export crash doesn't permanently leak disk space.
    val newCacheEntries = mutableListOf<ClipRenderCacheEntry>()

    try {
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
                engineId = engine.engineId,
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
                    resolver = resolver,
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
    } catch (t: Throwable) {
        // Best-effort cleanup — delete every mezzanine we freshly produced
        // this run. Entries already in the cache (cache hits) are untouched.
        // Individual deletes go through runCatching so one failed delete
        // doesn't mask the original exception; CancellationException from
        // the outer scope still propagates because we re-throw t unchanged.
        for (entry in newCacheEntries) {
            runCatching { engine.deleteMezzanine(entry.mezzaninePath) }
        }
        throw t
    }
}
