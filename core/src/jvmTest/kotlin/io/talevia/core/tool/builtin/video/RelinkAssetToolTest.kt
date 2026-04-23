package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.domain.Resolution
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers RelinkAssetTool cascade semantics + FileProjectStore.openAt's
 * BusEvent.AssetsMissing emission.
 */
class RelinkAssetToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private data class Rig(
        val store: FileProjectStore,
        val bus: EventBus,
        val bundlePath: okio.Path,
    )

    private fun rig(): Rig {
        val tmpHome = createTempDirectory("relink-home")
        val bus = EventBus()
        val store = FileProjectStore(
            registry = RecentsRegistry(tmpHome.resolve("recents.json").toString().toPath()),
            defaultProjectsHome = tmpHome.toString().toPath(),
            bus = bus,
        )
        return Rig(store, bus, tmpHome.resolve("p").toString().toPath())
    }

    private fun fileAsset(id: String, path: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File(path),
        metadata = MediaMetadata(duration = 2.seconds, resolution = Resolution(640, 480)),
    )

    @Test fun relinkFlipsEveryAssetSharingTheOriginalPath() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        // Two assets sharing /bad/original, plus one distinct other path.
        rig.store.mutate(pid) {
            it.copy(
                assets = listOf(
                    fileAsset("a1", "/alice/raw.mp4"),
                    fileAsset("a2", "/alice/raw.mp4"),
                    fileAsset("b1", "/alice/other.mp4"),
                ),
            )
        }

        val tool = RelinkAssetTool(rig.store)
        val result = tool.execute(
            RelinkAssetTool.Input(assetId = "a1", newPath = "/bob/media/raw.mp4", projectId = pid.value),
            ctx(),
        )

        assertEquals(setOf("a1", "a2"), result.data.relinkedAssetIds.toSet())
        assertEquals("/alice/raw.mp4", result.data.originalPath)

        val project = rig.store.get(pid)!!
        val a1 = project.assets.single { it.id.value == "a1" }.source as MediaSource.File
        val a2 = project.assets.single { it.id.value == "a2" }.source as MediaSource.File
        val b1 = project.assets.single { it.id.value == "b1" }.source as MediaSource.File
        assertEquals("/bob/media/raw.mp4", a1.path)
        assertEquals("/bob/media/raw.mp4", a2.path, "sibling with same original path flips too")
        assertEquals("/alice/other.mp4", b1.path, "asset with different original path untouched")
    }

    @Test fun unknownAssetIdFailsLoud() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        val tool = RelinkAssetTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                RelinkAssetTool.Input(assetId = "ghost", newPath = "/bob/x.mp4", projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("ghost"))
        assertTrue(ex.message!!.contains("project_query(select=assets)"), "error should hint at discovery")
    }

    @Test fun bundleFileSourceRejectedByRelink() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        rig.store.mutate(pid) {
            it.copy(
                assets = listOf(
                    MediaAsset(
                        id = AssetId("aigc"),
                        source = MediaSource.BundleFile("media/aigc.png"),
                        metadata = MediaMetadata(duration = 0.seconds),
                    ),
                ),
            )
        }
        val tool = RelinkAssetTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                RelinkAssetTool.Input(assetId = "aigc", newPath = "/x", projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("BundleFile"))
    }

    @Test fun blankNewPathFailsLoud() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        rig.store.mutate(pid) {
            it.copy(assets = listOf(fileAsset("a", "/x.mp4")))
        }
        val tool = RelinkAssetTool(rig.store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                RelinkAssetTool.Input(assetId = "a", newPath = "   ", projectId = pid.value),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("newPath"))
    }

    @Test fun openAtEmitsAssetsMissingForNonExistentFilePaths() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        // Mix: one real file on disk, one ghost, one BundleFile (ignored by scan).
        val realDir = createTempDirectory("relink-real")
        val realPath = realDir.resolve("real.mp4")
        Files.write(realPath, byteArrayOf(1, 2, 3))

        rig.store.mutate(pid) {
            it.copy(
                assets = listOf(
                    fileAsset("live", realPath.toAbsolutePath().toString()),
                    fileAsset("ghost", "/tmp/nonexistent-${kotlin.random.Random.nextLong().toString(16)}.mp4"),
                    MediaAsset(
                        id = AssetId("bundle"),
                        source = MediaSource.BundleFile("media/bundle.png"),
                        metadata = MediaMetadata(duration = 0.seconds),
                    ),
                ),
            )
        }

        // Subscribe BEFORE publish — EventBus has no replay, so a subscriber
        // added after openAt would miss the event.
        val captured = CompletableDeferred<BusEvent.AssetsMissing>()
        val collector = backgroundScope.launch {
            captured.complete(
                rig.bus.events.filterIsInstance<BusEvent.AssetsMissing>().first(),
            )
        }
        yield()  // give the collector a cycle to register its subscription
        rig.store.openAt(rig.bundlePath)
        val payload = captured.await()
        collector.cancel()

        assertEquals(pid, payload.projectId)
        assertEquals(listOf("ghost"), payload.missing.map { it.assetId })
        assertTrue(payload.missing.single().originalPath.contains("nonexistent"))
    }

    @Test fun openAtEmitsNothingWhenAllFilePathsExist() = runTest {
        val rig = rig()
        val pid = rig.store.createAt(path = rig.bundlePath, title = "demo").id
        val realDir = createTempDirectory("relink-happy")
        val realPath = realDir.resolve("real.mp4")
        Files.write(realPath, byteArrayOf(1))
        rig.store.mutate(pid) {
            it.copy(assets = listOf(fileAsset("ok", realPath.toAbsolutePath().toString())))
        }

        // Race a collector with a short timeout against openAt; if no event
        // fires, the timeout wins and we get null.
        val captured = CompletableDeferred<BusEvent.AssetsMissing?>()
        val collector = backgroundScope.launch {
            captured.complete(
                withTimeoutOrNull(500L) {
                    rig.bus.events.filterIsInstance<BusEvent.AssetsMissing>().first()
                },
            )
        }
        yield()
        rig.store.openAt(rig.bundlePath)
        val payload = captured.await()
        collector.cancel()
        assertEquals(null, payload, "no missing assets → no event")
    }
}
