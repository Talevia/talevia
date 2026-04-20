package io.talevia.core.platform

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the key contract of a persistent MediaStorage: AssetIds survive
 * a restart. If this regresses, Projects saved in SQLite would reference
 * AssetIds that can no longer be resolved after restart and every saved
 * clip would fail during render.
 */
class FileMediaStorageTest {

    private lateinit var root: File

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("file-media-storage").toFile()
    }

    @AfterTest fun tearDown() {
        root.deleteRecursively()
    }

    private val probe: suspend (MediaSource) -> MediaMetadata = {
        MediaMetadata(duration = 5.seconds, resolution = Resolution(1920, 1080))
    }

    @Test fun importThenListReturnsAsset() = runBlocking {
        val storage = FileMediaStorage(root)
        val asset = storage.import(MediaSource.File("/tmp/a.mp4"), probe)
        val listed = storage.list()
        assertEquals(listOf(asset), listed)
        assertEquals("/tmp/a.mp4", storage.resolve(asset.id))
    }

    @Test fun importPersistsAcrossReinstantiation() = runBlocking {
        val first = FileMediaStorage(root)
        val asset = first.import(MediaSource.File("/tmp/clip.mov"), probe)

        // Simulate process restart by constructing a new storage pointing at
        // the same dir. The index.json written by [first] must rehydrate.
        val second = FileMediaStorage(root)
        val reloaded = second.get(asset.id)
        assertNotNull(reloaded, "asset must survive restart")
        assertEquals(asset, reloaded)
        assertEquals("/tmp/clip.mov", second.resolve(asset.id))
    }

    @Test fun deletePersistsRemoval() = runBlocking {
        val first = FileMediaStorage(root)
        val asset = first.import(MediaSource.File("/tmp/doomed.mp4"), probe)
        first.delete(asset.id)

        val second = FileMediaStorage(root)
        assertNull(second.get(asset.id), "deleted asset must not be resurrected on restart")
        assertTrue(second.list().isEmpty())
    }

    @Test fun resolveUnknownAssetFails() = runBlocking {
        val storage = FileMediaStorage(root)
        assertFailsWith<IllegalStateException> { storage.resolve(io.talevia.core.AssetId("nope")) }
        Unit
    }

    @Test fun emptyIndexFileLoadsAsEmpty() = runBlocking {
        // A zero-byte index.json (e.g. left behind by a truncated write from
        // an older version) should not crash construction.
        File(root, "index.json").writeText("")
        val storage = FileMediaStorage(root)
        assertTrue(storage.list().isEmpty())
    }

    @Test fun garbageIndexFileFailsLoudlyOnConstruction() {
        // Garbage (non-empty, non-JSON) is different from empty: empty is a
        // recoverable mid-write artefact, garbage suggests a different process
        // wrote here. We must NOT silently truncate; that would lose every
        // asset reference saved by the previous run.
        File(root, "index.json").writeText("{this is not json")
        assertFailsWith<Exception> { FileMediaStorage(root) }
    }

    @Test fun resolveAfterDeleteFails() = runBlocking {
        val storage = FileMediaStorage(root)
        val asset = storage.import(MediaSource.File("/tmp/gone.mp4"), probe)
        storage.delete(asset.id)
        assertFailsWith<IllegalStateException> { storage.resolve(asset.id) }
        Unit
    }

    @Test fun resolveHttpSourceFailsExplicitly() = runBlocking {
        // HTTP sources have to be downloaded before they're resolvable to a
        // local path; FileMediaStorage doesn't do that download itself, so
        // it MUST fail loudly rather than hand back a remote URL that the
        // ffmpeg / AVFoundation / Media3 path would mishandle.
        val storage = FileMediaStorage(root)
        val asset = storage.import(MediaSource.Http("https://example.com/x.mp4"), probe)
        val ex = assertFailsWith<IllegalStateException> { storage.resolve(asset.id) }
        assertTrue(ex.message!!.contains("Http"), "error should explain why: ${ex.message}")
    }

    @Test fun resolvePlatformSourceFailsExplicitly() = runBlocking {
        val storage = FileMediaStorage(root)
        val asset = storage.import(
            MediaSource.Platform(scheme = "ph", value = "asset-id-1"),
            probe,
        )
        val ex = assertFailsWith<IllegalStateException> { storage.resolve(asset.id) }
        assertTrue(ex.message!!.contains("Platform"), "error should explain why: ${ex.message}")
    }

    @Test fun parallelImportsAreSerializedAndAllPersisted() = runBlocking {
        // FileMediaStorage protects index.json with a mutex; the persist path
        // does atomic-move. Concurrent imports must all land in the index AND
        // be visible after restart — neither lost (mutex skipped) nor mangled
        // (atomic-move skipped). 16 parallel imports keeps the contention
        // realistic without ballooning runtime.
        val storage = FileMediaStorage(root)
        val parallelism = 16

        val assets = withContext(Dispatchers.Default) {
            (1..parallelism).map { i ->
                async {
                    storage.import(MediaSource.File("/tmp/clip-$i.mp4"), probe)
                }
            }.awaitAll()
        }

        assertEquals(parallelism, storage.list().size, "lost imports under contention")

        // Reload from disk — every imported asset must still be there.
        val reopened = FileMediaStorage(root)
        for (a in assets) assertNotNull(reopened.get(a.id), "asset ${a.id} missing after restart")
    }

    @Test fun resolverInterfaceTreatsStorageAsResolver() = runBlocking {
        // MediaPathResolver is a fun interface and FileMediaStorage extends it.
        // VideoEngine etc. depend on the narrower resolver type. This test
        // pins that the upcast doesn't break: a path resolved through the
        // upcast must equal the path resolved through the storage.
        val storage = FileMediaStorage(root)
        val asset = storage.import(MediaSource.File("/tmp/narrow.mp4"), probe)
        val resolver: MediaPathResolver = storage
        assertEquals(storage.resolve(asset.id), resolver.resolve(asset.id))

        val unknown = AssetId("never-imported")
        assertFailsWith<IllegalStateException> { resolver.resolve(unknown) }
        Unit
    }
}
