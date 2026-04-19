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
        return MediaMetadata(
            duration = durationSecs.seconds,
            resolution = resolution,
            frameRate = frameRate,
            videoCodec = video?.get("codec_name")?.jsonPrimitive?.contentOrNull,
            audioCodec = audio?.get("codec_name")?.jsonPrimitive?.contentOrNull,
            sampleRate = audio?.get("sample_rate")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            channels = audio?.get("channels")?.jsonPrimitive?.intOrNull,
            bitrate = format?.get("bit_rate")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
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
        val args = mutableListOf(
            ffmpegPath, "-y", "-progress", "pipe:2",
        )
        for ((clip, path) in videoClips.zip(resolvedPaths)) {
            args += listOf("-ss", "${clip.sourceRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-t", "${clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-i", path)
        }
        val n = videoClips.size
        // Build the filtergraph. For clips with per-clip filters, pre-process
        // the video stream through a chain and label it [vN]; clips without
        // filters pass [N:v:0] through unchanged. Audio is always taken from
        // [N:a:0?] without filters (apply_filter only supports video in core).
        val filter = buildString {
            val videoLabels = mutableListOf<String>()
            for (i in 0 until n) {
                val clip = videoClips[i]
                val chain = filterChainFor(clip.filters, filterAssetPaths)
                if (chain != null) {
                    append("[$i:v:0]$chain[v$i];")
                    videoLabels += "[v$i]"
                } else {
                    videoLabels += "[$i:v:0]"
                }
            }
            for (i in 0 until n) {
                append(videoLabels[i])
                append("[$i:a:0?]")
            }
            append("concat=n=$n:v=1:a=1[outv][outa]")
        }
        args += listOf("-filter_complex", filter)
        args += listOf("-map", "[outv]", "-map", "[outa]")
        args += listOf("-r", output.frameRate.toString())
        args += listOf("-s", "${output.resolution.width}x${output.resolution.height}")
        args += listOf("-c:v", output.videoCodec, "-b:v", "${output.videoBitrate}")
        args += listOf("-c:a", output.audioCodec, "-b:a", "${output.audioBitrate}")
        args += output.targetPath

        val process = ProcessBuilder(args).redirectErrorStream(false).start()
        val totalSeconds = timeline.duration.toDouble(kotlin.time.DurationUnit.SECONDS).takeIf { it > 0 } ?: 1.0
        val reader = BufferedReader(InputStreamReader(process.errorStream))

        try {
            // ffmpeg writes progress KV pairs (out_time_ms=…, progress=continue|end) to
            // stderr when -progress pipe:2 is set. runInterruptible wraps the blocking
            // readLine so a cancellation on the collecting coroutine interrupts the
            // thread instead of stalling until ffmpeg produces its next line.
            while (true) {
                val line = runInterruptible(ioDispatcher) { reader.readLine() } ?: break
                val k = line.substringBefore('=', "")
                val v = line.substringAfter('=', "")
                if (k == "out_time_ms" && v.isNotEmpty()) {
                    val microsec = v.toLongOrNull()
                    if (microsec != null) {
                        val ratio = (microsec / 1_000_000.0 / totalSeconds).coerceIn(0.0, 1.0)
                        emit(RenderProgress.Frames(jobId, ratio.toFloat()))
                    }
                }
                if (k == "progress" && v == "end") break
            }
            val exit = runInterruptible(ioDispatcher) { process.waitFor() }
            if (exit == 0) emit(RenderProgress.Completed(jobId, output.targetPath))
            else emit(RenderProgress.Failed(jobId, "ffmpeg exited $exit"))
        } finally {
            // Always kill the child and drain the stderr reader — even if the caller
            // cancelled mid-flight. destroyForcibly is idempotent.
            runCatching { reader.close() }
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor() }
            }
        }
    }.flowOn(ioDispatcher)

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
}
