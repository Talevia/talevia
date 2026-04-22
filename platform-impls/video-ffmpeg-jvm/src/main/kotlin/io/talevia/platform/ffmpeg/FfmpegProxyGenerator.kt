package io.talevia.platform.ffmpeg

import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProxyAsset
import io.talevia.core.domain.ProxyPurpose
import io.talevia.core.domain.Resolution
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.ProxyGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Generate thumbnail proxies for freshly-imported video / image assets via
 * system `ffmpeg`. VISION §5.3 performance lane: a 4K import should not force
 * every UI consumer to decode the full asset just to render a scrub preview.
 *
 * Thumbnail strategy:
 *  - **Video**: seek to `durationSeconds / 2` and emit one JPEG scaled to
 *    `THUMB_WIDTH` (preserve aspect). Mid-frame is the classic gallery thumb
 *    choice — title frame is often letterbox, last frame is often a fade to
 *    black.
 *  - **Image**: single-pass scale to `THUMB_WIDTH`.
 *  - **Audio-only**: render a waveform PNG via
 *    `ffmpeg -filter_complex "showwavespic=s=<WxH>:colors=white"` (VISION §5.3
 *    performance lane — audio UIs can then render the scrub strip from the
 *    PNG instead of decoding the full container each time). The resulting
 *    proxy lands with `purpose=AUDIO_WAVEFORM`, distinct from the
 *    `THUMBNAIL` proxies video/image assets get, so consumers can route on
 *    purpose.
 *
 * Output lives in a dedicated sibling directory under the media dir
 * (`<media>/proxies/<assetId>/`) so the UI can cheap-list them and the proxy
 * files don't pollute the main storage catalog. When the generator is given
 * no writable parent (tmpRoot resolve fails), it falls back to the system
 * temp dir — still usable, but the files disappear on reboot.
 *
 * **Best-effort.** Per the [ProxyGenerator] contract, implementation failures
 * return an empty list instead of throwing: ffmpeg missing from PATH, an
 * unreadable container, a short / zero-duration asset — each produces a
 * silent empty result so the import itself still succeeds. Logs go to
 * stderr of the ffmpeg child process; enable `FFMPEG_PROXY_DEBUG=1` to
 * forward them to this process's stderr for debugging.
 */
class FfmpegProxyGenerator(
    private val pathResolver: MediaPathResolver,
    private val ffmpegPath: String = "ffmpeg",
    private val proxyDir: File? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProxyGenerator {

    override suspend fun generate(asset: MediaAsset): List<ProxyAsset> {
        val sourcePath = runCatching { pathResolver.resolve(asset.id) }.getOrNull()
            ?: return emptyList()
        if (!File(sourcePath).exists()) return emptyList()

        val outDir = resolveProxyDir(asset.id.value) ?: return emptyList()

        val isImage = asset.metadata.videoCodec == null &&
            asset.metadata.audioCodec == null &&
            asset.metadata.duration <= Duration.ZERO &&
            asset.metadata.resolution != null
        val hasVideo = asset.metadata.videoCodec != null
        val isAudioOnly = asset.metadata.videoCodec == null &&
            asset.metadata.audioCodec != null &&
            asset.metadata.duration > Duration.ZERO

        if (isAudioOnly) {
            val waveformFile = File(outDir, "waveform.png")
            val generated = generateWaveform(sourcePath, waveformFile) ?: return emptyList()
            return listOf(
                ProxyAsset(
                    source = MediaSource.File(generated.absolutePath),
                    purpose = ProxyPurpose.AUDIO_WAVEFORM,
                    resolution = Resolution(width = WAVEFORM_WIDTH, height = WAVEFORM_HEIGHT),
                ),
            )
        }

        val thumbFile = File(outDir, "thumb.jpg")
        val thumbMs: Long = when {
            hasVideo -> halfDurationMs(asset.metadata.duration) ?: return emptyList()
            isImage -> 0L
            else -> return emptyList()
        }

        val generated = generateThumbnail(
            sourcePath = sourcePath,
            outputFile = thumbFile,
            seekMs = thumbMs,
        ) ?: return emptyList()

        return listOf(
            ProxyAsset(
                source = MediaSource.File(generated.absolutePath),
                purpose = ProxyPurpose.THUMBNAIL,
                resolution = thumbnailResolution(asset.metadata.resolution),
            ),
        )
    }

    private fun resolveProxyDir(assetId: String): File? {
        val root = proxyDir ?: defaultProxyRoot()
        val dir = File(root, assetId)
        return runCatching { Files.createDirectories(dir.toPath()).toFile() }.getOrNull()
    }

    private fun defaultProxyRoot(): File {
        // System temp is safe for test + single-shot use; production apps
        // override via the explicit proxyDir arg (e.g. ~/.talevia/proxies).
        val tmp = System.getProperty("java.io.tmpdir") ?: "/tmp"
        val root = File(tmp, "talevia-proxies")
        return runCatching { Files.createDirectories(root.toPath()).toFile() }.getOrDefault(root)
    }

    /**
     * Run the `showwavespic` filter to emit a single PNG rendering the full
     * audio waveform. `s=WIDTHxHEIGHT` sizes the output; `colors=white`
     * picks a foreground that reads on dark UI backgrounds (consumers can
     * tint post-hoc). Best-effort: missing ffmpeg / unreadable audio / a
     * container with no decodable audio stream → null, caller returns an
     * empty list the same way thumbnail generation does.
     */
    private suspend fun generateWaveform(
        sourcePath: String,
        outputFile: File,
    ): File? = runInterruptible(ioDispatcher) {
        val args = listOf(
            ffmpegPath,
            "-y",
            "-i", sourcePath,
            "-filter_complex", "showwavespic=s=${WAVEFORM_WIDTH}x$WAVEFORM_HEIGHT:colors=white",
            "-frames:v", "1",
            outputFile.absolutePath,
        )
        val debug = System.getenv("FFMPEG_PROXY_DEBUG") == "1"
        val process = ProcessBuilder(args)
            .redirectErrorStream(debug)
            .redirectOutput(if (debug) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.DISCARD)
            .also {
                if (!debug) it.redirectError(ProcessBuilder.Redirect.DISCARD)
            }
            .start()
        val code = process.waitFor()
        if (code == 0 && outputFile.exists() && outputFile.length() > 0) outputFile else null
    }

    private suspend fun generateThumbnail(
        sourcePath: String,
        outputFile: File,
        seekMs: Long,
    ): File? = runInterruptible(ioDispatcher) {
        val args = buildList {
            add(ffmpegPath)
            add("-y") // overwrite — idempotent re-runs.
            if (seekMs > 0) {
                add("-ss")
                add(String.format("%.3f", seekMs / 1000.0))
            }
            add("-i")
            add(sourcePath)
            add("-vframes")
            add("1")
            add("-vf")
            add("scale=$THUMB_WIDTH:-2")
            add(outputFile.absolutePath)
        }
        val debug = System.getenv("FFMPEG_PROXY_DEBUG") == "1"
        val process = ProcessBuilder(args)
            .redirectErrorStream(debug)
            .redirectOutput(if (debug) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.DISCARD)
            .also {
                if (!debug) it.redirectError(ProcessBuilder.Redirect.DISCARD)
            }
            .start()
        val code = process.waitFor()
        if (code == 0 && outputFile.exists() && outputFile.length() > 0) outputFile else null
    }

    private fun halfDurationMs(duration: Duration): Long? {
        val ms = duration.toLong(DurationUnit.MILLISECONDS)
        if (ms <= 0) return null
        return ms / 2
    }

    private fun thumbnailResolution(source: Resolution?): Resolution? {
        if (source == null) return null
        if (source.width <= 0) return null
        val h = (source.height * THUMB_WIDTH.toDouble() / source.width).toInt().coerceAtLeast(1)
        return Resolution(width = THUMB_WIDTH, height = h)
    }

    // Path extractor lives here so tests don't need an absolute-path
    // MediaPathResolver import. Mirrors FfmpegVideoEngine's usage pattern.
    @Suppress("unused")
    private fun File.abs(): String = toPath().absolutePathString()

    private companion object {
        private const val THUMB_WIDTH = 320

        // Waveform sized for UI scrub strips (compact banner, not album art).
        // 640×80 gives ~2px per second on a 5-minute track, enough peak
        // resolution to read transient energy without blowing up the PNG
        // size past the thumbnail band.
        private const val WAVEFORM_WIDTH = 640
        private const val WAVEFORM_HEIGHT = 80
    }
}
