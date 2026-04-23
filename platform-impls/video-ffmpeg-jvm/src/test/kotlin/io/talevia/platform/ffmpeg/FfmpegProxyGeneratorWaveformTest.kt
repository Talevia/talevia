package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProxyPurpose
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

    @Test fun audioOnlyAssetProducesWaveformProxy() = runTest(timeout = 60.seconds) {
        if (!ffmpegOnPath()) return@runTest
        val audioFile = File(workDir, "sine.m4a")
        generateAudioOnlySource(audioFile)
        val assetId = AssetId("a-1")
        val proxyDir = File(workDir, "proxies")
        val gen = FfmpegProxyGenerator(proxyDir = proxyDir)
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(audioFile.absolutePath),
            metadata = MediaMetadata(
                duration = 2.seconds,
                videoCodec = null,
                audioCodec = "aac",
            ),
        )
        val proxies = gen.generate(asset, audioFile.absolutePath)
        assertEquals(1, proxies.size)
        val p = proxies.single()
        assertEquals(ProxyPurpose.AUDIO_WAVEFORM, p.purpose)
        val src = p.source as MediaSource.File
        val pngFile = File(src.path)
        assertTrue(pngFile.exists(), "waveform PNG must exist at ${pngFile.absolutePath}")
        assertTrue(pngFile.length() > 0L, "waveform PNG must be non-empty")
        val head = pngFile.inputStream().use { it.readNBytes(8) }
        assertEquals(
            listOf(-119, 80, 78, 71, 13, 10, 26, 10),
            head.map { it.toInt() },
            "proxy file must be a real PNG",
        )
    }

    @Test fun videoAssetStillProducesThumbnailNotWaveform() = runTest(timeout = 60.seconds) {
        if (!ffmpegOnPath()) return@runTest
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
        val gen = FfmpegProxyGenerator(proxyDir = File(workDir, "proxies"))
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(videoFile.absolutePath),
            metadata = MediaMetadata(
                duration = 2.seconds,
                videoCodec = "h264",
                audioCodec = "aac",
            ),
        )
        val proxies = gen.generate(asset, videoFile.absolutePath)
        assertEquals(1, proxies.size)
        assertEquals(ProxyPurpose.THUMBNAIL, proxies.single().purpose)
    }

    @Test fun audioMetadataWithoutDurationFallsThroughToEmptyNotWaveform() = runTest {
        if (!ffmpegOnPath()) return@runTest
        val audioFile = File(workDir, "zero.m4a")
        audioFile.createNewFile()
        val assetId = AssetId("a-zero")
        val gen = FfmpegProxyGenerator(proxyDir = File(workDir, "proxies"))
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(audioFile.absolutePath),
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                videoCodec = null,
                audioCodec = "aac",
            ),
        )
        val proxies = gen.generate(asset, audioFile.absolutePath)
        assertTrue(proxies.isEmpty(), "zero-duration audio must not produce a waveform proxy")
    }
}
