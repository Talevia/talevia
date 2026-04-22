package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ExtractFrameToolTest {

    /** Minimal valid 1x1 PNG. */
    private val stubPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
        0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
        0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
        0x42, 0x60.toByte(), 0x82.toByte(),
    )

    private class FakeVideoEngine(private val bytes: ByteArray) : VideoEngine {
        var lastTime: Duration? = null
            private set
        var lastAsset: AssetId? = null
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            error("probe should not be called by ExtractFrameTool")

        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> =
            emptyFlow()

        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray {
            lastAsset = asset
            lastTime = time
            return bytes
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : MediaBlobWriter {
        override suspend fun writeBlob(bytes: ByteArray, suggestedExtension: String): MediaSource {
            val file = File(rootDir, "${Files.list(rootDir.toPath()).count()}.$suggestedExtension")
            file.writeBytes(bytes)
            return MediaSource.File(file.absolutePath)
        }
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun importVideo(
        storage: InMemoryMediaStorage,
        duration: Duration,
        resolution: Resolution? = Resolution(1920, 1080),
    ) = storage.import(MediaSource.File("/tmp/video.mp4")) { _ ->
        MediaMetadata(duration = duration, resolution = resolution, frameRate = null)
    }

    @Test fun extractsFrameAndRegistersAsset() = runTest {
        val tmp = createTempDirectory("extract-frame-test").toFile()
        val storage = InMemoryMediaStorage()
        val engine = FakeVideoEngine(stubPng)
        val tool = ExtractFrameTool(engine, storage, FakeBlobWriter(tmp))

        val video = importVideo(storage, duration = 10.seconds)
        val result = tool.execute(
            ExtractFrameTool.Input(assetId = video.id.value, timeSeconds = 2.5),
            ctx(),
        )

        val out = result.data
        assertEquals(video.id.value, out.sourceAssetId)
        assertNotEquals(video.id.value, out.frameAssetId)
        assertEquals(2.5, out.timeSeconds)
        assertEquals(1920, out.width)
        assertEquals(1080, out.height)

        // Engine saw the right timestamp + asset
        assertEquals(2.5.seconds, engine.lastTime)
        assertEquals(video.id, engine.lastAsset)

        // New asset resolves to a real file holding the frame bytes
        val framePath = storage.resolve(AssetId(out.frameAssetId))
        assertTrue(File(framePath).exists())
        assertEquals(stubPng.toList(), File(framePath).readBytes().toList())
    }

    @Test fun inheritsSourceResolutionWhenPresent() = runTest {
        val tmp = createTempDirectory("extract-frame-test-2").toFile()
        val storage = InMemoryMediaStorage()
        val tool = ExtractFrameTool(FakeVideoEngine(stubPng), storage, FakeBlobWriter(tmp))

        val video = importVideo(storage, duration = 5.seconds, resolution = Resolution(640, 360))
        val result = tool.execute(
            ExtractFrameTool.Input(assetId = video.id.value, timeSeconds = 1.0),
            ctx(),
        )

        val frame = storage.get(AssetId(result.data.frameAssetId))!!
        assertEquals(Resolution(640, 360), frame.metadata.resolution)
        assertEquals(Duration.ZERO, frame.metadata.duration)
    }

    @Test fun recordsNullResolutionWhenSourceHasNone() = runTest {
        val tmp = createTempDirectory("extract-frame-test-3").toFile()
        val storage = InMemoryMediaStorage()
        val tool = ExtractFrameTool(FakeVideoEngine(stubPng), storage, FakeBlobWriter(tmp))

        val video = importVideo(storage, duration = 5.seconds, resolution = null)
        val result = tool.execute(
            ExtractFrameTool.Input(assetId = video.id.value, timeSeconds = 0.5),
            ctx(),
        )

        assertEquals(null, result.data.width)
        assertEquals(null, result.data.height)
    }

    @Test fun rejectsNegativeTimestamp() = runTest {
        val tmp = createTempDirectory("extract-frame-test-4").toFile()
        val storage = InMemoryMediaStorage()
        val tool = ExtractFrameTool(FakeVideoEngine(stubPng), storage, FakeBlobWriter(tmp))

        val video = importVideo(storage, duration = 5.seconds)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ExtractFrameTool.Input(assetId = video.id.value, timeSeconds = -0.1),
                ctx(),
            )
        }
    }

    @Test fun rejectsTimestampPastDuration() = runTest {
        val tmp = createTempDirectory("extract-frame-test-5").toFile()
        val storage = InMemoryMediaStorage()
        val tool = ExtractFrameTool(FakeVideoEngine(stubPng), storage, FakeBlobWriter(tmp))

        val video = importVideo(storage, duration = 3.seconds)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ExtractFrameTool.Input(assetId = video.id.value, timeSeconds = 10.0),
                ctx(),
            )
        }
    }

    @Test fun failsOnUnknownAsset() = runTest {
        val tmp = createTempDirectory("extract-frame-test-6").toFile()
        val storage = InMemoryMediaStorage()
        val tool = ExtractFrameTool(FakeVideoEngine(stubPng), storage, FakeBlobWriter(tmp))

        assertFailsWith<IllegalStateException> {
            tool.execute(
                ExtractFrameTool.Input(assetId = "nope", timeSeconds = 0.0),
                ctx(),
            )
        }
    }
}
