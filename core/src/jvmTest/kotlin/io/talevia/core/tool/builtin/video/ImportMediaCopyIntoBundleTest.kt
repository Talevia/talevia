package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the new `copy_into_bundle = true` branch of [ImportMediaTool].
 *
 * The default branch ("import by reference") is exercised via
 * [ImportMediaBatchTest] / [ImportMediaProxyTest]; this test focuses on
 * the bundle-copy semantics: bytes land at `<bundleRoot>/media/<assetId>.<ext>`
 * and the asset is registered as [MediaSource.BundleFile] rather than
 * [MediaSource.File].
 */
class ImportMediaCopyIntoBundleTest {

    /** Stub engine — every probe succeeds with the same metadata. */
    private class StubVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata = MediaMetadata(
            duration = 5.seconds,
            resolution = Resolution(640, 360),
            videoCodec = "h264",
        )
        override fun render(
            timeline: Timeline,
            output: OutputSpec,
            resolver: io.talevia.core.platform.MediaPathResolver?,
        ): Flow<RenderProgress> = flowOf(RenderProgress.Failed("no-op", "stub"))
        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = ByteArray(0)
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun copyIntoBundleCopiesBytesAndRegistersBundleFileSource() = runTest {
        // Real filesystem for the source bytes (java.io.File temp dir),
        // FileProjectStore for the destination bundle (real filesystem too,
        // since FileBundleBlobWriter / our copy code uses okio's
        // FileSystem.SYSTEM by default and FileProjectStore needs a real fs
        // when we want it to also resolve paths via toPath()).
        val tmpSrc = createTempDirectory("import-bundle-src")
        val srcFile = Files.createFile(tmpSrc.resolve("hero.mp4"))
        Files.write(srcFile, byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        val srcAbs = srcFile.toAbsolutePath().toString()

        val tmpHome = createTempDirectory("import-bundle-home")
        val recents = tmpHome.resolve("recents.json")
        val store = io.talevia.core.domain.FileProjectStore(
            registry = io.talevia.core.domain.RecentsRegistry(
                recents.toString().toPath(),
            ),
            defaultProjectsHome = tmpHome.toString().toPath(),
        )
        val bundlePath = tmpHome.resolve("p1").toString().toPath()
        val pid = store.createAt(path = bundlePath, title = "import-bundle").id

        val tool = ImportMediaTool(InMemoryMediaStorage(), StubVideoEngine(), store)
        val result = tool.execute(
            ImportMediaTool.Input(
                path = srcAbs,
                projectId = pid.value,
                copy_into_bundle = true,
            ),
            ctx(),
        )

        // Asset is on the project; source is BundleFile, not File.
        val project = store.get(pid)!!
        val asset = project.assets.single()
        val src = asset.source as MediaSource.BundleFile
        assertTrue(src.relativePath.startsWith("media/"))
        assertTrue(src.relativePath.endsWith(".mp4"))
        // Bytes were copied into the bundle at the resolved path.
        val resolved = bundlePath.resolve(src.relativePath)
        val onDisk = okio.FileSystem.SYSTEM.read(resolved) { readByteArray() }
        assertEquals(listOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte()), onDisk.toList())

        // Output mirrors normal import shape — first asset id is exposed flat.
        assertEquals(asset.id.value, result.data.assetId)
        assertEquals(1, result.data.imported.size)
    }

    @Test fun copyIntoBundleFalseRegistersFileSourceAsBefore() = runTest {
        val tmpSrc = createTempDirectory("import-ref-src")
        val srcFile = Files.createFile(tmpSrc.resolve("rush.mp4"))
        Files.write(srcFile, byteArrayOf(0xFA.toByte(), 0xCE.toByte()))
        val srcAbs = srcFile.toAbsolutePath().toString()

        val store = ProjectStoreTestKit.create()
        store.upsert("demo", io.talevia.core.domain.Project(id = io.talevia.core.ProjectId("p-ref"), timeline = Timeline()))

        val tool = ImportMediaTool(InMemoryMediaStorage(), StubVideoEngine(), store)
        tool.execute(
            ImportMediaTool.Input(path = srcAbs, projectId = "p-ref"),
            ctx(),
        )

        val asset = store.get(io.talevia.core.ProjectId("p-ref"))!!.assets.single()
        val src = asset.source as MediaSource.File
        assertEquals(srcAbs, src.path, "default copy_into_bundle=false keeps the original absolute path")
    }
}
