package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Clip
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText
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
        val result = run(
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
        val args = mutableListOf(
            ffmpegPath, "-y", "-progress", "pipe:2",
        )
        for ((clip, path) in videoClips.zip(resolvedPaths)) {
            args += listOf("-ss", "${clip.sourceRange.start.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-t", "${clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS)}")
            args += listOf("-i", path)
        }
        val n = videoClips.size
        val filter = buildString {
            for (i in 0 until n) append("[$i:v:0][$i:a:0?]")
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
            // ffmpeg writes progress KV pairs (out_time_ms=…, progress=continue|end) to stderr when -progress pipe:2 is set.
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                val (k, v) = l.substringBefore('=', "") to l.substringAfter('=', "")
                if (k == "out_time_ms" && v.isNotEmpty()) {
                    val microsec = v.toLongOrNull() ?: continue
                    val ratio = (microsec / 1_000_000.0 / totalSeconds).coerceIn(0.0, 1.0)
                    emit(RenderProgress.Frames(jobId, ratio.toFloat()))
                }
                if (k == "progress" && v == "end") break
            }
            val exit = process.waitFor()
            if (exit == 0) emit(RenderProgress.Completed(jobId, output.targetPath))
            else emit(RenderProgress.Failed(jobId, "ffmpeg exited $exit"))
        } finally {
            runCatching { reader.close() }
            if (process.isAlive) process.destroyForcibly()
        }
    }.flowOn(ioDispatcher)

    override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray {
        val path = sourceToLocalPath(source)
        val tmp = Files.createTempFile("talevia-thumb-", ".png")
        try {
            val result = run(
                ffmpegPath, "-y", "-ss", "${time.toDouble(kotlin.time.DurationUnit.SECONDS)}",
                "-i", path, "-vframes", "1", tmp.absolutePathString(),
            )
            if (result.exitCode != 0) error("ffmpeg thumbnail failed: ${result.stderr.takeLast(200)}")
            return Files.readAllBytes(tmp)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
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

    private fun run(vararg args: String): ProcessResult {
        val proc = ProcessBuilder(*args).redirectErrorStream(false).start()
        val stdout = proc.inputStream.bufferedReader().use { it.readText() }
        val stderr = proc.errorStream.bufferedReader().use { it.readText() }
        val exit = proc.waitFor()
        return ProcessResult(exit, stdout, stderr)
    }
}
