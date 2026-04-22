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
 *  - **Audio**: skipped this round (waveform rendering is a distinct
 *    follow-up; `ProxyPurpose.AUDIO_WAVEFORM` exists but needs a different
 *    pipeline). Callers see an empty list and fall back to the decode-original
 *    path that already worked before this feature.
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
    }
}
