package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.render.TransitionFades
import kotlinx.coroutines.flow.Flow

/**
 * Per-platform video processing service. The Core defines this contract; each
 * platform supplies an implementation:
 *  - JVM (Desktop / Server): FfmpegVideoEngine (shells out to system ffmpeg/ffprobe)
 *  - iOS: AVFoundationVideoEngine (Swift, injected via SKIE)
 *  - Android: Media3VideoEngine
 */
interface VideoEngine {
    /** Inspect a media file and return its metadata. */
    suspend fun probe(source: MediaSource): MediaMetadata

    /**
     * Render the [timeline] into [output]; emit progress events as work proceeds.
     *
     * Optional [resolver] overrides any [MediaPathResolver] the engine was constructed with.
     * `ExportTool` passes a per-project [io.talevia.core.platform.BundleMediaPathResolver]
     * here so AIGC + bundle assets resolve relative to the loaded bundle's root rather
     * than via a global asset pool. Tests / standalone callers can pass `null` and rely
     * on whatever resolver the engine carries.
     */
    fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver? = null,
    ): Flow<RenderProgress>

    /** Generate a single-frame thumbnail at [time]. Returns PNG bytes. */
    suspend fun thumbnail(asset: AssetId, source: MediaSource, time: kotlin.time.Duration): ByteArray

    /**
     * Whether this engine implements the per-clip mezzanine render path
     * ([renderClip] + [concatMezzanines]) that powers [ExportTool]'s
     * [io.talevia.core.domain.render.ClipRenderCache] optimization (VISION §3.2 —
     * "只重编译必要的部分"). Default `false` so iOS / Android impls keep whole-timeline
     * semantics until they implement it; the FFmpeg/JVM impl opts in.
     *
     * When `false`, [ExportTool] always calls [render] for the whole timeline.
     */
    val supportsPerClipCache: Boolean get() = false

    /**
     * Render a single [Clip.Video] to a mezzanine file at [mezzaninePath] — an mp4
     * encoded at [output]'s resolution / fps / codec / bitrate so later mezzanines
     * can be concat-demuxed into the final export.
     *
     * Responsibilities:
     *  - Resolve the clip's source media via the engine's own [MediaPathResolver]
     *    (the engine already holds one; callers don't pass paths in) and seek
     *    into it via [Clip.Video.sourceRange].
     *  - Apply the clip's own filters (brightness, saturation, blur, vignette, LUT).
     *  - Bake in head/tail fades from [fades] — these came from a neighbouring
     *    transition clip on an Effect track; the clip itself doesn't know them.
     *  - Use bitexact flags so the mezzanine is byte-deterministic (required for
     *    [io.talevia.core.domain.render.ClipRenderCache] correctness — a cached
     *    fingerprint must produce the same bytes on a re-render).
     *  - Do NOT apply subtitles — they're timeline-relative and get applied in
     *    [concatMezzanines]. Keeping subtitles out of mezzanines means a subtitle
     *    edit only invalidates the final concat step, not any per-clip mezzanine.
     *
     * Default impl throws [UnsupportedOperationException] so engines that haven't
     * implemented the per-clip path remain spec-compliant; [supportsPerClipCache]
     * gates whether callers reach this method.
     */
    suspend fun renderClip(
        clip: Clip.Video,
        fades: TransitionFades?,
        output: OutputSpec,
        mezzaninePath: String,
        resolver: MediaPathResolver? = null,
    ): Unit = throw UnsupportedOperationException(
        "renderClip not supported by ${this::class.simpleName} (supportsPerClipCache=$supportsPerClipCache)",
    )

    /**
     * Ask the engine whether a previously-rendered mezzanine is still present on
     * disk. Used by [ExportTool] to treat a missing mezzanine as a cache miss
     * (user deleted the `.talevia-render-cache` dir between exports) instead of
     * failing the concat step with an obscure "No such file" ffmpeg error.
     *
     * Default `true` so engines without a per-clip path — or targets where a
     * filesystem check isn't meaningful (in-memory storage, sandboxed iOS
     * containers) — don't reject cache hits. The FFmpeg/JVM impl overrides with
     * an actual `Files.exists(...)` check since mezzanines live in a predictable
     * directory on the host filesystem.
     */
    suspend fun mezzaninePresent(path: String): Boolean = true

    /**
     * Delete a previously-rendered mezzanine at [path]. Called by
     * [io.talevia.core.tool.builtin.project.GcClipRenderCacheTool] when a cache
     * entry is being pruned — the engine drops the on-disk mp4 so disk usage
     * tracks the cache policy. Returns `true` iff a file actually went (the
     * row count stays honest even when the user already nuked the file).
     *
     * Default returns `false` — engines without a per-clip path (Media3,
     * AVFoundation today) never wrote mezzanines, so there's nothing to
     * delete. The FFmpeg/JVM impl overrides with `Files.deleteIfExists(path)`.
     * Errors (permission denied, etc.) bubble up; the caller is expected to
     * let the tool result carry the per-entry outcome and keep going.
     */
    suspend fun deleteMezzanine(path: String): Boolean = false

    /**
     * Stitch pre-rendered mezzanine files (output of [renderClip]) at
     * [mezzaninePaths] into the final export at [output]. Applies [subtitles] as
     * a post-concat drawtext chain (they're timeline-relative so can't be baked
     * into mezzanines) and must remain bitexact so re-running the final step on
     * identical inputs produces the same bytes.
     *
     * The path of least resistance when [subtitles] is empty is FFmpeg's concat
     * demuxer with `-c copy`: the mezzanines were encoded at [output]'s profile
     * so stream-copy produces a valid mp4 without re-encoding — which is where
     * the §3.2 speed-up actually lands.
     *
     * Default impl throws [UnsupportedOperationException].
     */
    suspend fun concatMezzanines(
        mezzaninePaths: List<String>,
        subtitles: List<Clip.Text>,
        output: OutputSpec,
    ): Unit = throw UnsupportedOperationException(
        "concatMezzanines not supported by ${this::class.simpleName} (supportsPerClipCache=$supportsPerClipCache)",
    )
}

data class OutputSpec(
    val targetPath: String,
    val resolution: Resolution,
    val frameRate: Int = 30,
    val videoBitrate: Long = 8_000_000,
    val audioBitrate: Long = 192_000,
    val videoCodec: String = "h264",
    val audioCodec: String = "aac",
    val container: String = "mp4",
    /**
     * Container-level metadata key→value entries the engine should bake into
     * the output file (e.g. mp4 `moov.udta` / ISO BMFF metadata atoms).
     * Typical use: [io.talevia.core.domain.render.ProvenanceManifest] encoded
     * into a `"comment"` entry so the artifact traces back to its source
     * Project + Timeline hash (VISION §5.3). Default empty — engines that
     * cannot write container metadata (Media3 / AVFoundation today) silently
     * ignore the map; FFmpeg engine wires it via `-metadata key=value`.
     *
     * Values must be deterministic across re-exports of the same Project to
     * preserve the bit-exact RenderCache guarantee — no timestamps, no
     * process ids, no machine-local identifiers.
     */
    val metadata: Map<String, String> = emptyMap(),
)

sealed interface RenderProgress {
    data class Started(val jobId: String) : RenderProgress
    data class Frames(val jobId: String, val ratio: Float, val message: String? = null) : RenderProgress

    /**
     * An intermediate low-resolution JPEG snapshot of the in-progress render
     * (VISION §5.4 — long exports stay watchable / interruptible; the expert
     * path can inspect output mid-stream and decide to cancel + reshape the
     * timeline before the full encode completes).
     *
     * [thumbnailPath] points at a best-effort on-disk JPEG the engine wrote;
     * it is **mutable** (engines typically overwrite the same file each tick
     * via an `image2 -update 1`-style side output). Consumers that want to
     * hold on to a specific tick's bytes must copy them out before the next
     * `Preview` event fires. The path's lifetime ends when [Completed] or
     * [Failed] is emitted — the engine is free to delete it.
     *
     * Optional: engines without a cheap side-output (Media3, AVFoundation)
     * simply never emit this variant; consumers must not rely on its
     * presence.
     */
    data class Preview(val jobId: String, val ratio: Float, val thumbnailPath: String) : RenderProgress

    data class Completed(val jobId: String, val outputPath: String) : RenderProgress
    data class Failed(val jobId: String, val message: String) : RenderProgress
}
