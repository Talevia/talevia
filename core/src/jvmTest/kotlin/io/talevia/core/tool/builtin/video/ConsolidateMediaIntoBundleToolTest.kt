package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `consolidate_media_into_bundle`: `MediaSource.File` → `BundleFile`
 * flip with byte-copy, already-bundled skip, missing-file failure capture,
 * Http/Platform unsupported count, and idempotency.
 */
class ConsolidateMediaIntoBundleToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    /**
     * Rig uses real filesystem because `ConsolidateMediaIntoBundleTool`
     * defaults to `FileSystem.SYSTEM` and exercising the okio streaming
     * copy on the real FS matches the production path closer than a
     * FakeFileSystem swap.
     */
    private data class Rig(
        val store: io.talevia.core.domain.FileProjectStore,
        val bundlePath: okio.Path,
        val srcDir: java.nio.file.Path,
    )

    private fun rig(): Rig {
        val tmpHome = createTempDirectory("consolidate-home")
        val recents = tmpHome.resolve("recents.json")
        val store = io.talevia.core.domain.FileProjectStore(
            registry = io.talevia.core.domain.RecentsRegistry(
                recents.toString().toPath(),
            ),
            defaultProjectsHome = tmpHome.toString().toPath(),
        )
        val bundlePath = tmpHome.resolve("p").toString().toPath()
        val srcDir = createTempDirectory("consolidate-src")
        return Rig(store, bundlePath, srcDir)
    }

    private fun fakeAsset(id: String, source: MediaSource): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = source,
        metadata = MediaMetadata(duration = 3.seconds, resolution = Resolution(640, 480)),
    )

    private fun writeBytes(rig: Rig, name: String, bytes: ByteArray): String {
        val p = rig.srcDir.resolve(name)
        Files.write(p, bytes)
        return p.toAbsolutePath().toString()
    }

    @Test fun consolidatesFileSourceAndLeavesBundleFileAlone() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        val heroPath = writeBytes(rig, "hero.mp4", byteArrayOf(1, 2, 3, 4, 5))
        rig.store.mutate(pid) { p ->
            p.copy(
                assets = listOf(
                    fakeAsset("a-file", MediaSource.File(heroPath)),
                    fakeAsset("a-bundle", MediaSource.BundleFile("media/a-bundle.png")),
                ),
            )
        }

        val tool = ConsolidateMediaIntoBundleTool(rig.store)
        val out = tool.execute(ConsolidateMediaIntoBundleTool.Input(projectId = pid.value), ctx()).data

        assertEquals(1, out.consolidated.size)
        assertEquals("a-file", out.consolidated.single().assetId)
        assertEquals("media/a-file.mp4", out.consolidated.single().bundleRelativePath)
        assertEquals(1, out.alreadyBundled)
        assertTrue(out.failures.isEmpty())

        val project = rig.store.get(pid)!!
        val a = project.assets.single { it.id.value == "a-file" }
        val src = a.source as MediaSource.BundleFile
        assertEquals("media/a-file.mp4", src.relativePath)
        // Byte copy actually landed.
        val copied = okio.FileSystem.SYSTEM.read(rig.bundlePath.resolve(src.relativePath)) { readByteArray() }
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), copied.toList())
    }

    @Test fun missingSourceReportsFailureWithoutAbortingBatch() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        val goodPath = writeBytes(rig, "ok.mp4", byteArrayOf(9, 9))
        rig.store.mutate(pid) { p ->
            p.copy(
                assets = listOf(
                    fakeAsset("good", MediaSource.File(goodPath)),
                    fakeAsset("ghost", MediaSource.File("/tmp/definitely-does-not-exist-$testSalt.mp4")),
                ),
            )
        }

        val tool = ConsolidateMediaIntoBundleTool(rig.store)
        val out = tool.execute(ConsolidateMediaIntoBundleTool.Input(projectId = pid.value), ctx()).data

        assertEquals(listOf("good"), out.consolidated.map { it.assetId })
        assertEquals(listOf("ghost"), out.failures.map { it.assetId })
        assertTrue(out.failures.single().error.contains("not found"))

        // Good one was still migrated even though ghost failed.
        val project = rig.store.get(pid)!!
        assertTrue(project.assets.single { it.id.value == "good" }.source is MediaSource.BundleFile)
        assertTrue(project.assets.single { it.id.value == "ghost" }.source is MediaSource.File)
    }

    @Test fun idempotent_SecondCallIsANoOp() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        val p = writeBytes(rig, "clip.mp4", byteArrayOf(0xAA.toByte()))
        rig.store.mutate(pid) {
            it.copy(assets = listOf(fakeAsset("c", MediaSource.File(p))))
        }

        val tool = ConsolidateMediaIntoBundleTool(rig.store)
        val first = tool.execute(ConsolidateMediaIntoBundleTool.Input(projectId = pid.value), ctx()).data
        val second = tool.execute(ConsolidateMediaIntoBundleTool.Input(projectId = pid.value), ctx()).data

        assertEquals(1, first.consolidated.size)
        assertEquals(0, second.consolidated.size)
        assertEquals(1, second.alreadyBundled)
        assertTrue(second.failures.isEmpty())
    }

    @Test fun httpAndPlatformSourcesCountedAsUnsupported() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        rig.store.mutate(pid) {
            it.copy(
                assets = listOf(
                    fakeAsset("web", MediaSource.Http("https://example.com/clip.mp4")),
                    fakeAsset("ios", MediaSource.Platform("phasset", "local-id")),
                ),
            )
        }

        val tool = ConsolidateMediaIntoBundleTool(rig.store)
        val out = tool.execute(ConsolidateMediaIntoBundleTool.Input(projectId = pid.value), ctx()).data

        assertEquals(2, out.unsupportedSourceCount)
        assertEquals(0, out.consolidated.size)
        assertTrue(out.failures.isEmpty())
        // Sources unchanged.
        val project = rig.store.get(pid)!!
        assertFalse(project.assets.any { it.source is MediaSource.BundleFile })
    }

    /** Unique per-test run salt so repeated tests don't hit the same "ghost" path. */
    private val testSalt = kotlin.random.Random.nextLong().toString(radix = 16)
}
