package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.TransitionFades
import io.talevia.core.domain.render.transitionFadesPerClip
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Shells out to system `ffmpeg` and `ffprobe`. Avoids the JNI / native-binary
 * weight of JavaCV at the cost of requiring the binaries on PATH.
 *
 * Render scope (M2): a single video track of sequential VideoClips concatenated
 * with `concat` filter. Multi-track audio mixing, transitions and filters are
 * later milestones.
 */
@OptIn(ExperimentalUuidApi::class)
class FfmpegVideoEngine(
    private val pathResolver: MediaPathResolver,
    private val ffmpegPath: String = "ffmpeg",
    private val ffprobePath: String = "ffprobe",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoEngine {

    override suspend fun probe(source: MediaSource): MediaMetadata {
        val path = sourceToLocalPath(source)
        val result = runProcess(
            ffprobePath,
            "-v", "error",
            "-print_format", "json",
            "-show_streams",
            "-show_format",
            path,
        )
        if (result.exitCode != 0) {
            error("ffprobe failed (${result.exitCode}): ${result.stderr.takeLast(400)}")
        }
        val root = Json.parseToJsonElement(result.stdout).jsonObject
        val format = root["format"]?.jsonObject
        val streams = root["streams"]?.jsonArray.orEmpty()
        val video = streams.firstOrNull { it.jsonObject["codec_type"]?.jsonPrimitive?.contentOrNull == "video" }?.jsonObject
        val audio = streams.firstOrNull { it.jsonObject["codec_type"]?.jsonPrimitive?.contentOrNull == "audio" }?.jsonObject

        val durationSecs = format?.get("duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val resolution = video?.let {
            val w = it["width"]?.jsonPrimitive?.intOrNull
            val h = it["height"]?.jsonPrimitive?.intOrNull
            if (w != null && h != null) Resolution(w, h) else null
        }
        val frameRate = video?.get("r_frame_rate")?.jsonPrimitive?.contentOrNull?.let(::parseFrameRate)
        // Container-level `comment` tag — lives under `format.tags.comment`.
        // Talevia exports bake a ProvenanceManifest here (VISION §5.3);
        // non-Talevia files either omit the tag (null) or carry arbitrary
        // user text (still surfaced as-is, decoder distinguishes via the
        // manifest prefix).
        val comment = format?.get("tags")?.jsonObject?.get("comment")?.jsonPrimitive?.contentOrNull
        return MediaMetadata(
            duration = durationSecs.seconds,
            resolution = resolution,
            frameRate = frameRate,
            videoCodec = video?.get("codec_name")?.jsonPrimitive?.contentOrNull,
            audioCodec = audio?.get("codec_name")?.jsonPrimitive?.contentOrNull,
            sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            channels = audio?.get("channels")?.jsonPrimitive?.intOrNull,
            bitrate = format?.get("bit_rate")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            comment = comment,
        )
    }

    override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> = flow {
        val jobId = Uuid.random().toString()
        emit(RenderProgress.Started(jobId))

        val videoTrack = timeline.tracks.firstOrNull { it is Track.Video }
        if (videoTrack == null || videoTrack.clips.isEmpty()) {
            emit(RenderProgress.Failed(jobId, "no video clips to render"))
            return@flow
        }
        val videoClips = videoTrack.clips.filterIsInstance<Clip.Video>()
        if (videoClips.isEmpty()) {
            emit(RenderProgress.Failed(jobId, "video track has no Video clips"))
            return@flow
        }

        val resolvedPaths = videoClips.map { clip -> pathResolver.resolve(clip.assetId) }
        // Pre-resolve asset-backed filter references (e.g. LUT files) to absolute
        // paths. Done here so filterChainFor() stays non-suspend and unit-testable.
        val filterAssetPaths: Map<AssetId, String> = videoClips
            .flatMap { it.filters }
            .mapNotNull { it.assetId }
            .distinct()
            .associateWith { pathResolver.resolve(it) }
        // Transition pass. AddTransitionTool records a transition as a clip on
        // an Effect track, centered at the boundary between two adjacent video
        // clips. For v1 we render every transition name as a dip-to-black fade
        // (fromClip tail fades to black over D/2; toClip head fades in from
        // black over D/2) — this is the cross-engine parity floor and matches
        // what the Media3 / AVFoundation engines can render without custom
        // shaders.
        val transitionFades: Map<String, TransitionFades> = transitionFadesFor(timeline, videoClips)
        // `-fflags +bitexact` strips the container-level encoder id string and
        // random/timestamp-based metadata; `-flags:v/+flags:a +bitexact` do the
        // same for video and audio streams (codec headers). Guarantees the
        // same `Project` + same `OutputProfile` produce **byte-identical**
        // output across re-invocations — a `RenderCache` correctness
        // precondition. Pinned by `ExportDeterminismTest`; see
        // `docs/decisions/2026-04-21-export-variant-deterministic-hash.md`.
        val args = mutableListOf(
            ffmpegPath, "-y", "-progress", "pipe:2",
            "-fflags", "+bitexact",
        )
        for ((clip, path) in videoClips.zip(resolvedPaths)) {
            args += listOf("-ss", "${clip.sourceRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-t", "${clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-i", path)
        }
        val n = videoClips.size
        // Collect Text clips from every Subtitle track. These render as
        // `drawtext` overlays bolted onto [outv] after the concat filter.
        // Other tracks (Effect, extra Audio) are ignored for now — same
        // scope as M2's "single video track" rule.
        val subtitleClips: List<Clip.Text> = timeline.tracks
            .filterIsInstance<Track.Subtitle>()
            .flatMap { it.clips.filterIsInstance<Clip.Text>() }
        val drawtextChain = subtitleDrawtextChain(
            subtitleClips,
            output.resolution.width,
            output.resolution.height,
        )
        // Build the filtergraph. For clips with per-clip filters or transition
        // fades, pre-process the video stream through a chain and label it [vN];
        // clips without either pass [N:v:0] through unchanged. Audio is always
        // taken from [N:a:0?] without filters (apply_filter only supports video
        // in core).
        //
        // Progressive preview (VISION §5.4). We tee the final video label via
        // `split` into [outv_main] (the export file's source) and [preview]
        // (a downsampled, frame-rate-limited side stream). The preview is
        // consumed by a second ffmpeg output — an image2 muxer with
        // `-update 1` that rewrites the same JPEG on each emitted frame. The
        // progress loop polls that file's mtime and emits a
        // [RenderProgress.Preview] event whenever a new frame lands.
        val previewPath = previewSidecarPathFor(output.targetPath, jobId)
        val filter = buildString {
            val videoLabels = mutableListOf<String>()
            for (i in 0 until n) {
                val clip = videoClips[i]
                val filterChain = filterChainFor(clip.filters, filterAssetPaths)
                val fadeChain = buildFadeChain(clip, transitionFades[clip.id.value])
                val fullChain = listOfNotNull(filterChain, fadeChain)
                    .ifEmpty { null }?.joinToString(",")
                if (fullChain != null) {
                    append("[$i:v:0]$fullChain[v$i];")
                    videoLabels += "[v$i]"
                } else {
                    videoLabels += "[$i:v:0]"
                }
            }
            for (i in 0 until n) {
                append(videoLabels[i])
                append("[$i:a:0?]")
            }
            append("concat=n=$n:v=1:a=1")
            if (drawtextChain != null) {
                // concat emits [cv]/[outa]; drawtext chain consumes [cv] and labels [outv_joined].
                append("[cv][outa];[cv]$drawtextChain[outv_joined]")
            } else {
                append("[outv_joined][outa]")
            }
            // Fan [outv_joined] into two branches: the real export ([outv_main])
            // and the preview pipeline ([preview] — ~1 frame per output second,
            // scaled to PREVIEW_WIDTH for a small JPEG).
            append(";[outv_joined]split=2[outv_main][preview_src];")
            append("[preview_src]fps=$PREVIEW_FPS,scale=$PREVIEW_WIDTH:-2[preview]")
        }
        // Make sure the preview sidecar's parent exists before ffmpeg tries to
        // open it. Same reason `-y` on the main output matters: fresh runs
        // shouldn't fail on a missing dir.
        Files.createDirectories(java.nio.file.Path.of(previewPath).parent)
        args += listOf("-filter_complex", filter)
        // Primary export output.
        args += listOf("-map", "[outv_main]", "-map", "[outa]")
        args += listOf("-r", output.frameRate.toString())
        args += listOf("-s", "${output.resolution.width}x${output.resolution.height}")
        args += listOf("-c:v", output.videoCodec, "-b:v", "${output.videoBitrate}", "-flags:v", "+bitexact")
        args += listOf("-c:a", output.audioCodec, "-b:a", "${output.audioBitrate}", "-flags:a", "+bitexact")
        // Container-level metadata — ExportTool bakes the ProvenanceManifest
        // as a `comment=<talevia/v1:{json}>` entry so the artifact carries
        // its source Project + Timeline hash (VISION §5.3). ffmpeg applies
        // `-metadata` to the NEXT output file, so these must precede
        // [output.targetPath]. Values passed through verbatim — callers are
        // responsible for keeping them deterministic (no timestamps, etc.)
        // per the ExportDeterminismTest bit-exact contract.
        //
        // `-map_metadata -1` (dropped input metadata) is required whenever
        // we bake user metadata: ffmpeg's default is to copy first-input
        // global metadata into the output container, which on probed
        // sources can include non-deterministic tags (input creation_time,
        // provider-specific encoder watermarks) that would defeat the
        // bit-exact render-cache contract even though +bitexact is set.
        // With `-1` the output carries ONLY what we explicitly set here.
        args += listOf("-map_metadata", "-1")
        for ((k, v) in output.metadata) {
            args += listOf("-metadata", "$k=$v")
        }
        args += output.targetPath
        // Preview side output — `-update 1 -f image2` makes ffmpeg overwrite
        // the same JPEG on each emitted frame (the canonical "live thumbnail"
        // recipe in ffmpeg docs). `-q:v 5` is a mid-quality JPEG — small
        // enough to poll cheaply, sharp enough to preview by.
        args += listOf("-map", "[preview]", "-c:v", "mjpeg", "-q:v", "5", "-update", "1", "-f", "image2", previewPath)

        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val totalSeconds = timeline.duration.toDouble(kotlin.time.DurationUnit.SECONDS).takeIf { it > 0 } ?: 1.0
        val reader = BufferedReader(InputStreamReader(process.errorStream))
        // Keep a rolling tail of non-progress stderr lines so a failure surfaces
        // ffmpeg's actual complaint (bad filter graph, missing input, codec
        // unsupported) instead of just "exited N".
        val stderrTail = ArrayDeque<String>()
        // Track the last-seen mtime of the preview JPEG so we emit exactly one
        // Preview event per ffmpeg overwrite. Starts at 0 — the first non-zero
        // mtime wins.
        var lastPreviewMtimeMs = 0L
        var lastPreviewRatio = 0f
        val previewFile = java.nio.file.Path.of(previewPath)

        try {
            // ffmpeg writes progress KV pairs (out_time_ms=…, progress=continue|end) to
            // stderr when -progress pipe:2 is set. runInterruptible wraps the blocking
            // readLine so a cancellation on the collecting coroutine interrupts the
            // thread instead of stalling until ffmpeg produces its next line.
            while (true) {
                val line = runInterruptible(ioDispatcher) { reader.readLine() } ?: break
                val k = line.substringBefore('=', "")
                val v = line.substringAfter('=', "")
                val isProgressKv = k == "out_time_ms" ||
                    k == "progress" ||
                    k == "frame" ||
                    k == "fps" ||
                    k == "stream_0_0_q" ||
                    k == "bitrate" ||
                    k == "total_size" ||
                    k == "out_time_us" ||
                    k == "out_time" ||
                    k == "dup_frames" ||
                    k == "drop_frames" ||
                    k == "speed"
                if (!isProgressKv && line.isNotBlank()) {
                    stderrTail.addLast(line)
                    if (stderrTail.size > 40) stderrTail.removeFirst()
                }
                if (k == "out_time_ms" && v.isNotEmpty()) {
                    val microsec = v.toLongOrNull()
                    if (microsec != null) {
                        val ratio = (microsec / 1_000_000.0 / totalSeconds).coerceIn(0.0, 1.0).toFloat()
                        emit(RenderProgress.Frames(jobId, ratio))
                        lastPreviewRatio = ratio
                    }
                }
                // Piggy-back on the progress KV cadence to check whether ffmpeg
                // has written a new preview frame. Cheap — one `Files.getLastModifiedTime`
                // call per KV line.
                if (Files.isRegularFile(previewFile)) {
                    val mtime = runCatching { Files.getLastModifiedTime(previewFile).toMillis() }.getOrDefault(0L)
                    if (mtime > lastPreviewMtimeMs) {
                        lastPreviewMtimeMs = mtime
                        emit(RenderProgress.Preview(jobId, lastPreviewRatio, previewPath))
                    }
                }
                if (k == "progress" && v == "end") break
            }
            val exit = runInterruptible(ioDispatcher) { process.waitFor() }
            if (exit == 0) {
                emit(RenderProgress.Completed(jobId, output.targetPath))
            } else {
                // Skip the banner/config preamble (noise) and keep only tail
                // lines that resemble actual errors — they typically contain
                // "Error", "No such", or an `[AVFilterGraph…]` prefix.
                val signal = stderrTail.filter { line ->
                    "Error" in line ||
                        "No such" in line ||
                        line.startsWith("[AV") ||
                        "Invalid" in line ||
                        "failed" in line ||
                        "cannot" in line.lowercase()
                }
                val summary = (signal.ifEmpty { stderrTail.toList() })
                    .joinToString(" | ")
                    .take(1200)
                emit(RenderProgress.Failed(jobId, "ffmpeg exited $exit: $summary"))
            }
        } finally {
            // Always kill the child and drain the stderr reader — even if the caller
            // cancelled mid-flight. destroyForcibly is idempotent.
            runCatching { reader.close() }
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor() }
            }
            // Best-effort preview-sidecar cleanup. `RenderProgress.Preview`'s
            // contract promises the path is valid until Completed/Failed —
            // callers that cared about a specific frame copied the bytes out
            // already.
            runCatching { Files.deleteIfExists(previewFile) }
        }
    }.flowOn(ioDispatcher)

    override val supportsPerClipCache: Boolean = true

    override suspend fun mezzaninePresent(path: String): Boolean {
        // A zero-byte file is an aborted mezzanine — treat as miss so we re-render
        // instead of feeding ffmpeg's concat demuxer garbage.
        val p = java.nio.file.Path.of(path)
        return Files.isRegularFile(p) && Files.size(p) > 0
    }

    /**
     * Render one [Clip.Video] to [mezzaninePath] as an mp4 encoded at [output]'s
     * profile. Powers [io.talevia.core.tool.builtin.video.ExportTool]'s per-clip
     * cache path — cache-miss clips become mezzanines, cache-hit clips reuse the
     * prior file, and [concatMezzanines] stitches them at the end.
     *
     * Shape:
     *  - Single input with `-ss` pre-seek + `-t` duration — cheapest way to trim.
     *  - Filtergraph mirrors the whole-timeline render's per-clip segment: the
     *    clip's own [Clip.Video.filters] followed by the [fades] fade chain.
     *    Subtitles are intentionally absent (they're timeline-relative; applied
     *    in [concatMezzanines]).
     *  - `-map` picks up the clip's audio stream if present (`0:a:0?` — optional
     *    means a silent render when the source has no audio track).
     *  - Encoder flags mirror the whole-timeline path (`-fflags +bitexact`,
     *    `+bitexact` on video + audio codec headers) so mezzanines are
     *    byte-deterministic — a second render with the same fingerprint produces
     *    the same bytes. Required for concat-demux correctness.
     */
    override suspend fun renderClip(
        clip: Clip.Video,
        fades: TransitionFades?,
        output: OutputSpec,
        mezzaninePath: String,
    ) {
        // Make sure the target directory exists — callers pass a path under
        // `<outputDir>/.talevia-render-cache/<projectId>/` which may not exist
        // on the first mezzanine write.
        Files.createDirectories(java.nio.file.Path.of(mezzaninePath).parent)

        val sourceMediaPath = pathResolver.resolve(clip.assetId)
        val filterAssetPaths: Map<AssetId, String> = clip.filters
            .mapNotNull { it.assetId }
            .distinct()
            .associateWith { pathResolver.resolve(it) }

        val filterChain = filterChainFor(clip.filters, filterAssetPaths)
        val fadeChain = buildFadeChain(clip, fades)
        val fullChain = listOfNotNull(filterChain, fadeChain)
            .ifEmpty { null }?.joinToString(",")

        val args = mutableListOf(
            ffmpegPath, "-y",
            "-fflags", "+bitexact",
            "-ss", "${clip.sourceRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)}",
            "-t", "${clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS)}",
            "-i", sourceMediaPath,
        )
        if (fullChain != null) {
            args += listOf(
                "-filter_complex", "[0:v:0]$fullChain[outv]",
                "-map", "[outv]", "-map", "0:a:0?",
            )
        } else {
            args += listOf("-map", "0:v:0", "-map", "0:a:0?")
        }
        args += listOf("-r", output.frameRate.toString())
        args += listOf("-s", "${output.resolution.width}x${output.resolution.height}")
        args += listOf("-c:v", output.videoCodec, "-b:v", "${output.videoBitrate}", "-flags:v", "+bitexact")
        args += listOf("-c:a", output.audioCodec, "-b:a", "${output.audioBitrate}", "-flags:a", "+bitexact")
        args += mezzaninePath

        val result = runProcess(*args.toTypedArray())
        if (result.exitCode != 0) {
            error(
                "ffmpeg mezzanine render failed (exit=${result.exitCode}) for clip ${clip.id.value} → $mezzaninePath: " +
                    result.stderr.takeLast(800),
            )
        }
    }

    /**
     * Concat the pre-encoded [mezzaninePaths] (output of [renderClip]) into the
     * final [output]. Two code paths:
     *
     * - **No subtitles**: `ffmpeg -f concat -c copy` stream-copies the mezzanine
     *   bitstreams straight into the target container. Zero re-encode cost —
     *   this is where the §3.2 speed-up lands.
     * - **With subtitles**: concat-demux the video+audio, apply the drawtext
     *   chain to the video stream, stream-copy audio, re-mux. Video must be
     *   re-encoded because drawtext is a per-frame filter, but it's still one
     *   encode pass over the whole timeline instead of N per-clip encodes.
     *
     * Concat-demux list file uses FFmpeg's single-quote + `'\''` escape (same
     * as shell) so paths containing `'` don't break out of the quoted value.
     */
    override suspend fun concatMezzanines(
        mezzaninePaths: List<String>,
        subtitles: List<Clip.Text>,
        output: OutputSpec,
    ) {
        require(mezzaninePaths.isNotEmpty()) { "concatMezzanines called with no mezzanines" }
        // Final output directory may not exist if user picked a fresh path; the
        // whole-timeline `render` path relies on ffmpeg to auto-create but the
        // concat demuxer can fail on missing parent dirs on some platforms.
        Files.createDirectories(java.nio.file.Path.of(output.targetPath).parent)

        val listFile = Files.createTempFile("talevia-concat-", ".txt")
        try {
            val listContent = mezzaninePaths.joinToString("\n") { path ->
                // Concat demuxer directive syntax: `file '<path>'`. Single quote
                // escape is `'\''` (close quote, escaped quote, open quote).
                "file '${path.replace("'", "'\\''")}'"
            }
            Files.writeString(listFile, listContent)

            val drawtextChain = subtitleDrawtextChain(
                subtitles,
                output.resolution.width,
                output.resolution.height,
            )

            val args = mutableListOf(
                ffmpegPath, "-y",
                "-fflags", "+bitexact",
                "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePathString(),
            )
            if (drawtextChain == null) {
                // Pure stream copy — the mezzanines already encoded at target
                // profile. `-flags:v/a +bitexact` is a no-op under copy but
                // keeps the args surface consistent with the whole-timeline path.
                args += listOf("-c", "copy", "-flags:v", "+bitexact", "-flags:a", "+bitexact")
            } else {
                args += listOf(
                    "-filter_complex", "[0:v]$drawtextChain[outv]",
                    "-map", "[outv]", "-map", "0:a?",
                    "-c:v", output.videoCodec, "-b:v", "${output.videoBitrate}", "-flags:v", "+bitexact",
                    "-c:a", "copy", "-flags:a", "+bitexact",
                )
            }
            // Container-level metadata (VISION §5.3). Per-clip mezzanines
            // never carry the manifest — only the final concat output does,
            // so the per-clip-cache path produces the same comment tag as
            // the whole-timeline path.
            for ((k, v) in output.metadata) {
                args += listOf("-metadata", "$k=$v")
            }
            args += output.targetPath

            val result = runProcess(*args.toTypedArray())
            if (result.exitCode != 0) {
                error(
                    "ffmpeg concat failed (exit=${result.exitCode}) for ${mezzaninePaths.size} mezzanines → " +
                        "${output.targetPath}: ${result.stderr.takeLast(800)}",
                )
            }
        } finally {
            runCatching { Files.deleteIfExists(listFile) }
        }
    }

    override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray {
        val path = sourceToLocalPath(source)
        val tmp = Files.createTempFile("talevia-thumb-", ".png")
        try {
            val result = runProcess(
                ffmpegPath, "-y", "-ss", "${time.toDouble(kotlin.time.DurationUnit.SECONDS)}",
                "-i", path, "-vframes", "1", tmp.absolutePathString(),
            )
            if (result.exitCode != 0) error("ffmpeg thumbnail failed: ${result.stderr.takeLast(200)}")
            return Files.readAllBytes(tmp)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /**
     * Translate core [Filter]s into an ffmpeg filtergraph chain. Returns null
     * when the clip has no supported filters, so the caller can skip allocating
     * labels. Unknown filter names are dropped — the core model accepts any
     * name, but only the names documented on ApplyFilterTool / ApplyLutTool
     * render here.
     *
     * `resolvedAssetPaths` is required for asset-backed filters (currently just
     * `"lut"`): the render loop resolves [AssetId]s to absolute paths through
     * `MediaPathResolver` and passes the map in so this function can stay
     * non-suspend and unit-testable.
     */
    internal fun filterChainFor(
        filters: List<Filter>,
        resolvedAssetPaths: Map<AssetId, String> = emptyMap(),
    ): String? {
        val segments = filters.mapNotNull { renderFilter(it, resolvedAssetPaths) }
        return if (segments.isEmpty()) null else segments.joinToString(",")
    }

    private fun renderFilter(f: Filter, resolvedAssetPaths: Map<AssetId, String>): String? = when (f.name.lowercase()) {
        // eq brightness range is [-1.0, 1.0]; default 0.
        "brightness" -> {
            val v = f.params["intensity"] ?: f.params["value"] ?: 0f
            "eq=brightness=${formatFloat(v.coerceIn(-1f, 1f))}"
        }
        // eq saturation range is [0, 3]; default 1. Accept intensity as 0..1 mapping to 0..2.
        "saturation" -> {
            val raw = f.params["intensity"] ?: f.params["value"] ?: 1f
            val v = if (f.params.containsKey("intensity")) (raw * 2f).coerceIn(0f, 3f) else raw.coerceIn(0f, 3f)
            "eq=saturation=${formatFloat(v)}"
        }
        // gblur sigma; default 0.5. A friendlier knob called radius/intensity maps 0..1 to 0..10.
        "blur" -> {
            val sigma = f.params["sigma"] ?: f.params["radius"]?.let { (it * 10f).coerceIn(0f, 50f) } ?: 5f
            "gblur=sigma=${formatFloat(sigma)}"
        }
        "vignette" -> "vignette"
        // lut3d applies a 3D LUT file (.cube / .3dl). We drop silently when the
        // path can't be resolved — consistent with "unknown filter names are
        // dropped" rather than breaking a whole render for one missing asset.
        "lut" -> f.assetId?.let { resolvedAssetPaths[it] }?.let { path ->
            "lut3d=file=${escapeFiltergraphArg(path)}"
        }
        else -> null
    }

    /**
     * Build a comma-joined `drawtext` chain for the timeline's subtitle clips,
     * or `null` when there are no clips to render. Each clip contributes one
     * drawtext filter gated by `enable='between(t,start,end)'`, so the overlay
     * only shows within that clip's timeline range.
     *
     * Positioning: the MVP anchors subtitles center-bottom (`x=(w-text_w)/2`,
     * `y=H-48-text_h` for a 1080p-ish baseline — scales linearly with the
     * output height to keep the margin visually proportional). Custom
     * positioning is a later knob on [TextStyle].
     *
     * Font: ffmpeg's drawtext needs a font *file* (not a family name). Rather
     * than hardcoding a platform-specific path, we omit the `fontfile=` option
     * entirely and let ffmpeg fall back to its built-in default, which works
     * across Linux/macOS/Windows as long as ffmpeg was built with freetype.
     * TextStyle.fontFamily is preserved in core but treated as a hint here.
     */
    internal fun subtitleDrawtextChain(
        clips: List<Clip.Text>,
        outputWidth: Int,
        outputHeight: Int,
    ): String? {
        if (clips.isEmpty()) return null
        // Bottom margin scales with height so the caption sits ~4.4% from the
        // bottom edge regardless of resolution (48 / 1080 ≈ 0.044).
        val bottomMargin = (outputHeight * 48 / 1080).coerceAtLeast(16)
        val segments = clips.map { clip -> renderDrawtext(clip, bottomMargin) }
        return segments.joinToString(",")
    }

    private fun renderDrawtext(clip: Clip.Text, bottomMargin: Int): String {
        val style = clip.style
        val start = clip.timeRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)
        val end = clip.timeRange.end.toDouble(kotlin.time.DurationUnit.SECONDS)
        val opts = mutableListOf<String>()
        opts += "text=${escapeDrawtextText(clip.text)}"
        opts += "fontsize=${formatFloat(style.fontSize)}"
        opts += "fontcolor=${drawtextColor(style.color)}"
        val bg = style.backgroundColor
        if (bg != null) {
            opts += "box=1"
            opts += "boxcolor=${drawtextColor(bg)}"
            opts += "boxborderw=10"
        }
        opts += "x=(w-text_w)/2"
        opts += "y=h-text_h-$bottomMargin"
        // Gate the overlay to the clip's timeline range. `between` is
        // inclusive on the start side and exclusive on the end, matching
        // our TimeRange.end convention.
        opts += "enable=${escapeFiltergraphArg("between(t,${formatFloat(start.toFloat())},${formatFloat(end.toFloat())})")}"
        return "drawtext=${opts.joinToString(":")}"
    }

    /**
     * Escape a string for drawtext's `text=` value. Two escape layers apply:
     *
     * 1. **Filtergraph level** — we wrap the whole value in single quotes, so
     *    inside the quoted section `:` `,` `;` `[` `]` and `\` are all literal.
     *    Only `'` can't appear inside the quotes; per ffmpeg's quoting rules
     *    the standard idiom is to close-then-escape-then-reopen: `foo'\''bar`.
     *
     * 2. **Drawtext level** — after graph parsing strips the outer quotes, the
     *    filter processes `%{…}` expansions. A literal `%` must therefore be
     *    delivered as `\%` (which reaches drawtext as `\%` since `\` is literal
     *    inside the outer quotes).
     */
    private fun escapeDrawtextText(v: String): String {
        val escaped = v
            .replace("%", "\\%")
            .replace("'", "'\\''")
        return "'$escaped'"
    }

    /**
     * drawtext accepts `#RRGGBB`, `#RRGGBBAA`, or a named color. We strip a
     * leading `#` — not required by ffmpeg, but safer: `#` isn't a filtergraph
     * meta character yet but it can confuse shells in ad-hoc testing.
     * Unknown / malformed strings pass through unchanged; ffmpeg will reject
     * them at render time with a clear error.
     */
    private fun drawtextColor(hexOrName: String): String =
        if (hexOrName.startsWith("#")) "0x${hexOrName.drop(1)}" else hexOrName

    /**
     * Escape a string so it can appear as a filtergraph argument value. ffmpeg
     * treats `:` as an option separator, `,` / `;` as filter separators, and `\`
     * as its own escape, so paths containing those would otherwise get parsed
     * as filter syntax. Single-character backslash-escape is enough for the
     * set of chars that actually appear in filesystem paths.
     */
    private fun escapeFiltergraphArg(v: String): String =
        v.replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("[", "\\[")
            .replace("]", "\\]")

    private fun formatFloat(v: Float): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun parseFrameRate(raw: String): FrameRate? {
        val parts = raw.split('/')
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.let { FrameRate(it) }
            2 -> {
                val n = parts[0].toIntOrNull() ?: return null
                val d = parts[1].toIntOrNull() ?: return null
                if (d == 0) null else FrameRate(n, d)
            }
            else -> null
        }
    }

    private fun sourceToLocalPath(source: MediaSource): String = when (source) {
        is MediaSource.File -> source.path
        is MediaSource.Http -> error("Http MediaSource not supported by FfmpegVideoEngine yet (download first)")
        is MediaSource.Platform -> error("Platform MediaSource not supported on JVM (${source.scheme})")
    }

    /**
     * Scan [timeline] for transition clips and map each affected video clip's
     * id (string form) to its fade envelope. Thin wrapper that delegates to
     * [transitionFadesPerClip] in Core and rekeys by [ClipId.value] because
     * the render loop uses string ids for label lookups.
     */
    internal fun transitionFadesFor(
        timeline: Timeline,
        videoClips: List<Clip.Video>,
    ): Map<String, TransitionFades> =
        timeline.transitionFadesPerClip(videoClips).mapKeys { (k, _) -> k.value }

    /**
     * Emit an ffmpeg `fade` filter chain for a clip's transition envelope, or
     * null when the clip has no fades to apply. `fade=t=in:...` ramps from black
     * to the source image at the head; `fade=t=out:...` ramps to black at the
     * tail. We anchor the tail fade at `clipDur - tailDur` so it lands exactly
     * at the clip's end regardless of which input pre-seek ffmpeg used.
     */
    internal fun buildFadeChain(clip: Clip.Video, fades: TransitionFades?): String? {
        if (fades == null) return null
        val clipDur = clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS)
        val parts = mutableListOf<String>()
        fades.headFade?.let {
            val d = it.toDouble(kotlin.time.DurationUnit.SECONDS).coerceAtMost(clipDur)
            if (d > 0.0) parts += "fade=t=in:st=0:d=${formatFloat(d.toFloat())}:c=black"
        }
        fades.tailFade?.let {
            val d = it.toDouble(kotlin.time.DurationUnit.SECONDS).coerceAtMost(clipDur)
            val start = (clipDur - d).coerceAtLeast(0.0)
            if (d > 0.0) parts += "fade=t=out:st=${formatFloat(start.toFloat())}:d=${formatFloat(d.toFloat())}:c=black"
        }
        return if (parts.isEmpty()) null else parts.joinToString(",")
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Shell out and wait for completion. Wrapped in runInterruptible so a
     * coroutine cancellation (e.g. the agent being aborted) actually stops
     * waitFor instead of pinning the dispatcher thread on a runaway binary.
     * On interrupt we destroyForcibly the child before re-raising, which
     * prevents orphaned ffmpeg/ffprobe processes.
     */
    private suspend fun runProcess(vararg args: String): ProcessResult {
        val proc = ProcessBuilder(*args).redirectErrorStream(false).start()
        try {
            return runInterruptible(ioDispatcher) {
                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                val exit = proc.waitFor()
                ProcessResult(exit, stdout, stderr)
            }
        } catch (t: Throwable) {
            if (proc.isAlive) {
                proc.destroyForcibly()
                runCatching { proc.waitFor() }
            }
            throw t
        }
    }

    /**
     * Path ffmpeg writes progressive preview JPEGs to during [render]. Lives
     * next to the target output in a hidden dot-dir so a directory listing of
     * the user's output folder doesn't show a half-written thumbnail; the
     * sidecar is deleted on render completion regardless of success.
     */
    private fun previewSidecarPathFor(targetPath: String, jobId: String): String {
        val parent = targetPath.substringBeforeLast('/', missingDelimiterValue = ".")
        return "$parent/.talevia-preview-$jobId.jpg"
    }

    private companion object {
        /**
         * Output frames per rendered-video-second emitted by the preview
         * branch. 1 is plenty for a "mid-render check-in" UX and keeps the
         * `image2 -update 1` overwrite rate low enough that the polling
         * progress loop never misses a frame.
         */
        const val PREVIEW_FPS = 1

        /**
         * Scale the preview to this width preserving aspect (`-2` on the
         * height arg does the rounding). Same 320px as
         * [io.talevia.platform.ffmpeg.FfmpegProxyGenerator]'s THUMB_WIDTH so
         * consumers that render thumbnails in the UI don't need two different
         * scaling code paths.
         */
        const val PREVIEW_WIDTH = 320
    }
}
