package io.talevia.core.platform

import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Resolution
import kotlinx.coroutines.runBlocking
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
}
