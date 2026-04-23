package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
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

    /**
     * In-memory [BundleBlobWriter] that records what was written so tests can
     * assert the persisted payload without touching the filesystem.
     */
    private class FakeBundleBlobWriter : BundleBlobWriter {
        val written = mutableMapOf<AssetId, ByteArray>()

        override suspend fun writeBlob(
            projectId: io.talevia.core.ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            written[assetId] = bytes
            return MediaSource.BundleFile("media/${assetId.value}.$format")
        }
    }

    private data class Rig(
        val store: FileProjectStore,
        val tool: ExtractFrameTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val engine: FakeVideoEngine,
        val blobWriter: FakeBundleBlobWriter,
    )

    private suspend fun newRig(): Rig {
        val store = ProjectStoreTestKit.create()
        val engine = FakeVideoEngine(stubPng)
        val blobWriter = FakeBundleBlobWriter()
        val tool = ExtractFrameTool(engine, store, blobWriter)
        val project = Project(id = ProjectId("p"), timeline = Timeline())
        store.upsert("test", project)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            currentProjectId = project.id,
        )
        return Rig(store, tool, ctx, project.id, engine, blobWriter)
    }

    private suspend fun Rig.importVideo(
        duration: Duration,
        resolution: Resolution? = Resolution(1920, 1080),
    ): AssetId {
        val id = AssetId(Uuid.random().toString())
        val asset = MediaAsset(
            id = id,
            source = MediaSource.File("/tmp/video.mp4"),
            metadata = MediaMetadata(duration = duration, resolution = resolution, frameRate = null),
        )
        store.mutate(projectId) { it.copy(assets = it.assets + asset) }
        return id
    }

    @Test fun extractsFrameAndRegistersAsset() = runTest {
        val rig = newRig()
        val sourceId = rig.importVideo(duration = 10.seconds)

        val result = rig.tool.execute(
            ExtractFrameTool.Input(assetId = sourceId.value, timeSeconds = 2.5),
            rig.ctx,
        )

        val out = result.data
        assertEquals(sourceId.value, out.sourceAssetId)
        assertNotEquals(sourceId.value, out.frameAssetId)
        assertEquals(2.5, out.timeSeconds)
        assertEquals(1920, out.width)
        assertEquals(1080, out.height)

        assertEquals(2.5.seconds, rig.engine.lastTime)
        assertEquals(sourceId, rig.engine.lastAsset)

        val framePayload = rig.blobWriter.written[AssetId(out.frameAssetId)]
        assertTrue(framePayload != null, "frame bytes must have been written via BundleBlobWriter")
        assertEquals(stubPng.toList(), framePayload!!.toList())

        val updated = rig.store.get(rig.projectId)!!
        val frameAsset = updated.assets.single { it.id.value == out.frameAssetId }
        assertTrue(frameAsset.source is MediaSource.BundleFile)
    }

    @Test fun inheritsSourceResolutionWhenPresent() = runTest {
        val rig = newRig()
        val sourceId = rig.importVideo(duration = 5.seconds, resolution = Resolution(640, 360))

        val result = rig.tool.execute(
            ExtractFrameTool.Input(assetId = sourceId.value, timeSeconds = 1.0),
            rig.ctx,
        )

        val updated = rig.store.get(rig.projectId)!!
        val frame = updated.assets.single { it.id.value == result.data.frameAssetId }
        assertEquals(Resolution(640, 360), frame.metadata.resolution)
        assertEquals(Duration.ZERO, frame.metadata.duration)
    }

    @Test fun recordsNullResolutionWhenSourceHasNone() = runTest {
        val rig = newRig()
        val sourceId = rig.importVideo(duration = 5.seconds, resolution = null)

        val result = rig.tool.execute(
            ExtractFrameTool.Input(assetId = sourceId.value, timeSeconds = 0.5),
            rig.ctx,
        )

        assertEquals(null, result.data.width)
        assertEquals(null, result.data.height)
    }

    @Test fun rejectsNegativeTimestamp() = runTest {
        val rig = newRig()
        val sourceId = rig.importVideo(duration = 5.seconds)
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ExtractFrameTool.Input(assetId = sourceId.value, timeSeconds = -0.1),
                rig.ctx,
            )
        }
    }

    @Test fun rejectsTimestampPastDuration() = runTest {
        val rig = newRig()
        val sourceId = rig.importVideo(duration = 3.seconds)
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ExtractFrameTool.Input(assetId = sourceId.value, timeSeconds = 10.0),
                rig.ctx,
            )
        }
    }

    @Test fun failsOnUnknownAsset() = runTest {
        val rig = newRig()
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ExtractFrameTool.Input(assetId = "nope", timeSeconds = 0.0),
                rig.ctx,
            )
        }
    }
}
