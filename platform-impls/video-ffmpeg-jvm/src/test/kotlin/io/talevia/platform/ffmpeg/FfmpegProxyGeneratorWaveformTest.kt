package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProxyPurpose
import io.talevia.core.platform.MediaPathResolver
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * E2E coverage for the `audio-waveform-proxy-generator` cycle —
 * `FfmpegProxyGenerator` now detects audio-only assets and produces a
 * `showwavespic` PNG with `purpose=AUDIO_WAVEFORM` instead of skipping
 * them (pre-cycle behaviour). Video / image / zero-duration cases
 * preserved via negative assertions.
 *
 * Skipped automatically when `ffmpeg` is not on PATH.
 */
class FfmpegProxyGeneratorWaveformTest {

    private lateinit var workDir: File

    @BeforeTest fun setUp() { workDir = Files.createTempDirectory("talevia-waveform-test-").toFile() }
    @AfterTest fun tearDown() { workDir.deleteRecursively() }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun generateAudioOnlySource(target: File) {
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-c:a", "aac",
            target.absolutePath,
        )
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }
        check(proc.waitFor() == 0) { "ffmpeg failed to generate $target" }
    }

    private fun pathResolverFor(id: AssetId, path: String): MediaPathResolver =
        object : MediaPathResolver {
            override suspend fun resolve(assetId: AssetId): String {
                require(assetId == id) { "unexpected asset id: $assetId" }
                return path
            }
        }

    @Test fun audioOnlyAssetProducesWaveformProxy() = runTest(timeout = 60.seconds) {
        if (!ffmpegOnPath()) return@runTest
        val audioFile = File(workDir, "sine.m4a")
        generateAudioOnlySource(audioFile)
        val assetId = AssetId("a-1")
        val proxyDir = File(workDir, "proxies")
        val gen = FfmpegProxyGenerator(
            pathResolver = pathResolverFor(assetId, audioFile.absolutePath),
            proxyDir = proxyDir,
        )
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(audioFile.absolutePath),
            metadata = MediaMetadata(
                duration = 2.seconds,
                videoCodec = null,
                audioCodec = "aac",
            ),
        )
        val proxies = gen.generate(asset)
        assertEquals(1, proxies.size)
        val p = proxies.single()
        assertEquals(ProxyPurpose.AUDIO_WAVEFORM, p.purpose)
        val src = p.source as MediaSource.File
        val pngFile = File(src.path)
        assertTrue(pngFile.exists(), "waveform PNG must exist at ${pngFile.absolutePath}")
        assertTrue(pngFile.length() > 0L, "waveform PNG must be non-empty")
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        val head = pngFile.inputStream().use { it.readNBytes(8) }
        assertEquals(
            listOf(-119, 80, 78, 71, 13, 10, 26, 10),
            head.map { it.toInt() },
            "proxy file must be a real PNG",
        )
    }

    @Test fun videoAssetStillProducesThumbnailNotWaveform() = runTest(timeout = 60.seconds) {
        if (!ffmpegOnPath()) return@runTest
        // Sanity: the new branch must NOT intercept video assets.
        val videoFile = File(workDir, "testsrc.mp4")
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=24",
            "-f", "lavfi", "-i", "anullsrc=cl=stereo:r=44100",
            "-shortest",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            videoFile.absolutePath,
        )
        check(ProcessBuilder(args).redirectErrorStream(true).start().waitFor() == 0)
        val assetId = AssetId("v-1")
        val gen = FfmpegProxyGenerator(
            pathResolver = pathResolverFor(assetId, videoFile.absolutePath),
            proxyDir = File(workDir, "proxies"),
        )
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(videoFile.absolutePath),
            metadata = MediaMetadata(
                duration = 2.seconds,
                videoCodec = "h264",
                audioCodec = "aac",
            ),
        )
        val proxies = gen.generate(asset)
        assertEquals(1, proxies.size)
        assertEquals(ProxyPurpose.THUMBNAIL, proxies.single().purpose)
    }

    @Test fun audioMetadataWithoutDurationFallsThroughToEmptyNotWaveform() = runTest {
        if (!ffmpegOnPath()) return@runTest
        // Edge: audioCodec set but duration is zero. This shouldn't happen in
        // practice (a valid audio-only asset has positive duration), but the
        // detector must not try to render a waveform from a 0-ms clip — the
        // ffmpeg run would silently produce a blank PNG.
        val audioFile = File(workDir, "zero.m4a")
        // Create an empty file — generator must bail before calling ffmpeg.
        audioFile.createNewFile()
        val assetId = AssetId("a-zero")
        val gen = FfmpegProxyGenerator(
            pathResolver = pathResolverFor(assetId, audioFile.absolutePath),
            proxyDir = File(workDir, "proxies"),
        )
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(audioFile.absolutePath),
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                videoCodec = null,
                audioCodec = "aac",
            ),
        )
        // zero-duration audio → falls through to the "else" arm (not image, not
        // video, not isAudioOnly which requires duration > 0) → empty list.
        val proxies = gen.generate(asset)
        assertTrue(proxies.isEmpty(), "zero-duration audio must not produce a waveform proxy")
    }
}
