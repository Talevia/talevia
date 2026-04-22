package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.ProvenanceManifest
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the render-provenance-manifest contract end-to-end through real
 * ffmpeg + ffprobe:
 *  - A render with `OutputSpec.metadata["comment"] = manifest` bakes the
 *    string into the container.
 *  - `FfmpegVideoEngine.probe` reads it back as `MediaMetadata.comment`.
 *  - `ProvenanceManifest.decodeFromComment` reconstructs the original
 *    manifest field-for-field.
 *  - Re-rendering with identical inputs produces the same bytes despite
 *    the metadata being present — guards against `-fflags +bitexact`
 *    stripping user metadata (which would break RenderCache correctness
 *    and defeat the provenance feature simultaneously).
 *
 * Skipped automatically when ffmpeg isn't on PATH.
 */
class FfmpegProvenanceManifestTest {
    private lateinit var workDir: File
    private lateinit var source: File
    private lateinit var outputA: File
    private lateinit var outputB: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("talevia-ffmpeg-prov-").toFile()
        source = File(workDir, "src.mp4")
        outputA = File(workDir, "a.mp4")
        outputB = File(workDir, "b.mp4")
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun manifestRoundTripsThroughRenderAndProbe() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest
        generateTestSource(source)

        val assetId = AssetId("a1")
        val resolver = object : MediaPathResolver {
            override suspend fun resolve(assetId: AssetId): String = source.absolutePath
        }
        val engine = FfmpegVideoEngine(pathResolver = resolver)

        val manifest = ProvenanceManifest(
            projectId = "p-42",
            timelineHash = "t-abc",
            lockfileHash = "l-def",
        )
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("2s")),
                            sourceRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("2s")),
                            assetId = assetId,
                        ),
                    ),
                ),
            ),
            duration = kotlin.time.Duration.parse("2s"),
        )
        val spec = OutputSpec(
            targetPath = outputA.absolutePath,
            resolution = Resolution(320, 240),
            frameRate = 24,
            metadata = mapOf("comment" to manifest.encodeToComment()),
        )

        engine.render(timeline, spec).toList()
        assertTrue(outputA.exists())
        assertTrue(outputA.length() > 1024)

        // Probe the output and reconstruct the manifest.
        val probed = engine.probe(MediaSource.File(outputA.absolutePath))
        assertNotNull(probed.comment, "ffprobe must read the baked comment tag")
        assertTrue(
            probed.comment!!.startsWith(ProvenanceManifest.MANIFEST_PREFIX),
            "Baked comment must start with the Talevia prefix; got: ${probed.comment}",
        )
        val decoded = ProvenanceManifest.decodeFromComment(probed.comment)
        assertEquals(manifest, decoded)
    }

    @Test
    fun rerenderWithSameManifestProducesBitExactOutput() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        if (!ffmpegOnPath()) return@runTest
        generateTestSource(source)

        val assetId = AssetId("a1")
        val resolver = object : MediaPathResolver {
            override suspend fun resolve(assetId: AssetId): String = source.absolutePath
        }
        val engine = FfmpegVideoEngine(pathResolver = resolver)

        val manifest = ProvenanceManifest(
            projectId = "p-bit",
            timelineHash = "hash-t",
            lockfileHash = "hash-l",
        )
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("2s")),
                            sourceRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("2s")),
                            assetId = assetId,
                        ),
                    ),
                ),
            ),
            duration = kotlin.time.Duration.parse("2s"),
        )
        val metadataMap = mapOf("comment" to manifest.encodeToComment())

        engine.render(
            timeline,
            OutputSpec(
                targetPath = outputA.absolutePath,
                resolution = Resolution(320, 240),
                frameRate = 24,
                metadata = metadataMap,
            ),
        ).toList()
        engine.render(
            timeline,
            OutputSpec(
                targetPath = outputB.absolutePath,
                resolution = Resolution(320, 240),
                frameRate = 24,
                metadata = metadataMap,
            ),
        ).toList()

        // Both outputs must exist, be identical in size + byte content. The
        // metadata bake must not introduce non-deterministic bytes (no
        // creation-time tag leaking past -bitexact).
        assertTrue(outputA.exists() && outputB.exists())
        assertEquals(outputA.length(), outputB.length(), "Same inputs must produce same-size output")
        assertEquals(
            outputA.readBytes().toList(),
            outputB.readBytes().toList(),
            "Metadata-bake must preserve bit-exact determinism",
        )
    }

    @Test
    fun probeOfNonTaleviaFileReturnsNullCommentOrNonTaleviaString() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest
        // generateTestSource uses lavfi + libx264 without -metadata. The resulting
        // file should have no `comment` tag (or possibly an unrelated one),
        // decodeFromComment must reject either way.
        generateTestSource(source)
        val resolver = object : MediaPathResolver {
            override suspend fun resolve(assetId: AssetId): String = source.absolutePath
        }
        val engine = FfmpegVideoEngine(pathResolver = resolver)

        val probed = engine.probe(MediaSource.File(source.absolutePath))
        // Either null or some non-Talevia tag. decodeFromComment must
        // refuse to claim it.
        assertNull(
            ProvenanceManifest.decodeFromComment(probed.comment),
            "Non-Talevia comment must not decode to a manifest; got: ${probed.comment}",
        )
    }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun generateTestSource(target: File) {
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=24",
            "-f", "lavfi", "-i", "anullsrc=cl=stereo:r=44100",
            "-shortest",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            target.absolutePath,
        )
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }
        check(proc.waitFor() == 0) { "ffmpeg failed to generate $target" }
    }
}
