package io.talevia.android

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProxyAsset
import io.talevia.core.domain.ProxyPurpose
import io.talevia.core.domain.Resolution
import io.talevia.core.platform.ProxyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Android counterpart of `platform-impls/video-ffmpeg-jvm`'s
 * `FfmpegProxyGenerator`. VISION §5.3 parity — iOS / Android were still
 * bound to [io.talevia.core.platform.NoopProxyGenerator] so 4K imports
 * didn't get a thumbnail, forcing every UI pass to decode the full
 * asset.
 *
 * Strategy:
 *  - **Video**: pull a frame at mid-duration via
 *    [MediaMetadataRetriever.getFrameAtTime], scale to [THUMB_WIDTH]
 *    (preserve aspect), compress as JPEG, write under
 *    `<proxyDir>/<assetId>/thumb.jpg`, return one
 *    `ProxyAsset(purpose=THUMBNAIL)`.
 *  - **Image / audio-only**: skipped — `MediaMetadataRetriever` can't
 *    seek to an image frame (no duration) and has no waveform path.
 *    Parity with FFmpeg's image branch and audio-waveform branch is a
 *    future cycle; this first pass matches FFmpeg's pre-waveform
 *    behaviour (thumbnails for video only on Android).
 *
 * Best-effort per the [ProxyGenerator] contract — any exception
 * (corrupt container, codec missing) produces an empty list so the
 * import still succeeds. The retriever is always released in a
 * `finally` to avoid file handle leaks.
 *
 * Output dir default is under the Android app's cache tier
 * (`<cacheDir>/talevia-proxies`), matching [AndroidFileBlobWriter]'s
 * rationale: OS may evict under storage pressure, but proxies are
 * regeneratable from the source asset.
 */
class Media3ProxyGenerator(
    private val proxyDir: File,
) : ProxyGenerator {

    override suspend fun generate(asset: MediaAsset, sourcePath: String): List<ProxyAsset> = withContext(Dispatchers.IO) {
        if (!File(sourcePath).exists()) return@withContext emptyList()

        // Only video has a mid-frame extraction path on Android.
        // Image / audio-only → empty list (caller falls back to decode-original).
        val hasVideo = asset.metadata.videoCodec != null
        if (!hasVideo) return@withContext emptyList()
        val midMs = halfDurationMs(asset.metadata.duration) ?: return@withContext emptyList()

        val outDir = resolveProxyDir(asset.id.value) ?: return@withContext emptyList()
        val thumbFile = File(outDir, "thumb.jpg")

        runCatching {
            extractAndWriteFrame(sourcePath, thumbFile, midMs)
        }.getOrNull()?.let { res ->
            listOf(
                ProxyAsset(
                    source = MediaSource.File(thumbFile.absolutePath),
                    purpose = ProxyPurpose.THUMBNAIL,
                    resolution = res,
                ),
            )
        } ?: emptyList()
    }

    private fun extractAndWriteFrame(
        sourcePath: String,
        output: File,
        seekMs: Long,
    ): Resolution? {
        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        try {
            retriever.setDataSource(sourcePath)
            val positionUs = seekMs * 1000L
            frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = scaleToWidth(frame, THUMB_WIDTH)
            ByteArrayOutputStream().use { bout ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bout)
                output.writeBytes(bout.toByteArray())
            }
            return Resolution(width = scaled.width, height = scaled.height)
        } finally {
            frame?.recycle()
            retriever.release()
        }
    }

    /**
     * Scale [source] so its width matches [targetWidth], preserving aspect.
     * Returns [source] unchanged if it's already at target width. Uses
     * [Bitmap.createScaledBitmap] with filtering on (matches FFmpeg's
     * `scale=W:-2` with default bicubic-ish behaviour in the range our
     * thumbnails live).
     */
    private fun scaleToWidth(source: Bitmap, targetWidth: Int): Bitmap {
        if (source.width == targetWidth) return source
        val targetHeight = (source.height * targetWidth.toDouble() / source.width)
            .toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun resolveProxyDir(assetId: String): File? {
        val dir = File(proxyDir, assetId)
        return runCatching {
            dir.mkdirs()
            dir
        }.getOrNull()
    }

    private fun halfDurationMs(duration: Duration): Long? {
        val ms = duration.toLong(DurationUnit.MILLISECONDS)
        if (ms <= 0) return null
        return ms / 2
    }

    private companion object {
        private const val THUMB_WIDTH = 320
        private const val JPEG_QUALITY = 85
    }
}
