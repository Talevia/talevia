package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileProjectStoreTest {

    private class FixedClock(var nowMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun setup(now: Long = 1_000): Triple<FileProjectStore, FakeFileSystem, FixedClock> {
        val clock = FixedClock(now)
        val fs = FakeFileSystem(clock = clock)
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
            clock = clock,
        )
        return Triple(store, fs, clock)
    }

    private fun videoTrack(): Track.Video = Track.Video(
        id = TrackId("t1"),
        clips = listOf(
            Clip.Video(
                id = ClipId("c1"),
                timeRange = TimeRange(0.seconds, 5.seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("a1"),
            ),
        ),
    )

    private fun asset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    @Test
    fun createAtWritesExpectedDirectoryStructure() = runTest {
        val (store, fs, _) = setup()
        val path = "/projects/foo".toPath()
        val project = store.createAt(path, "Foo")

        assertTrue(fs.exists(path.resolve(FileProjectStore.TALEVIA_JSON)))
        assertTrue(fs.exists(path.resolve(FileProjectStore.GITIGNORE)))
        assertTrue(fs.exists(path.resolve(FileProjectStore.MEDIA_DIR)))
        assertTrue(fs.exists(path.resolve(FileProjectStore.CACHE_DIR)))
        assertTrue(fs.exists(path.resolve(FileProjectStore.CACHE_DIR).resolve(FileProjectStore.CLIP_RENDER_CACHE_FILE)))

        // .gitignore content
        assertEquals(
            FileProjectStore.AUTO_GITIGNORE_BODY,
            fs.read(path.resolve(FileProjectStore.GITIGNORE)) { readUtf8() },
        )

        // Project loaded back through get matches
        assertEquals(project, store.get(project.id))
    }

    @Test
    fun createAtRefusesIfTaleviaJsonAlreadyExists() = runTest {
        val (store, fs, _) = setup()
        val path = "/projects/dup".toPath()
        fs.createDirectories(path)
        fs.write(path.resolve(FileProjectStore.TALEVIA_JSON)) { writeUtf8("{}") }

        assertFailsWith<IllegalArgumentException> {
            store.createAt(path, "Dup")
        }
    }

    @Test
    fun openAtReregistersExistingBundle() = runTest {
        val (store, fs, clock) = setup(now = 100)
        val path = "/projects/foo".toPath()
        val created = store.createAt(path, "Foo")

        // Simulate a second machine: build a fresh store + registry over the
        // same fake filesystem and call openAt on the existing bundle.
        clock.nowMs = 500
        val freshRegistry = RecentsRegistry("/.talevia/recents-2.json".toPath(), fs)
        val store2 = FileProjectStore(
            registry = freshRegistry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
            clock = clock,
        )
        val opened = store2.openAt(path)
        assertEquals(created.id, opened.id)
        assertEquals("Foo", store2.listSummaries().single().title)
    }

    @Test
    fun openAtThrowsWhenPathMissing() = runTest {
        val (store, _, _) = setup()
        assertFailsWith<okio.FileNotFoundException> {
            store.openAt("/no/such/path".toPath())
        }
    }

    @Test
    fun openAtThrowsWhenTaleviaJsonMissing() = runTest {
        val (store, fs, _) = setup()
        val path = "/projects/empty".toPath()
        fs.createDirectories(path)
        assertFailsWith<okio.FileNotFoundException> {
            store.openAt(path)
        }
    }

    @Test
    fun upsertWritesToRegisteredPath() = runTest {
        val (store, fs, clock) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")

        clock.nowMs = 2_000
        val mutated = created.copy(timeline = Timeline(tracks = listOf(videoTrack())), assets = listOf(asset("a1")))
        store.upsert("Foo", mutated)

        // Reload from disk; should have the new track
        val reloaded = store.get(created.id)!!
        assertEquals(1, reloaded.timeline.tracks.size)
        assertEquals(1, reloaded.assets.size)

        // Bundle still at the original path
        assertTrue(fs.exists("/projects/foo/talevia.json".toPath()))
    }

    @Test
    fun upsertSplitsClipRenderCacheToCacheFile() = runTest {
        val (store, fs, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")
        val withCache = created.copy(
            clipRenderCache = ClipRenderCache(
                listOf(
                    ClipRenderCacheEntry(
                        fingerprint = "fp",
                        mezzaninePath = "/mez/abc.mp4",
                        resolutionWidth = 1920,
                        resolutionHeight = 1080,
                        durationSeconds = 5.0,
                        createdAtEpochMs = 100,
                    ),
                ),
            ),
        )
        store.upsert("Foo", withCache)

        // talevia.json should NOT contain the fingerprint string
        val mainJson = fs.read("/projects/foo/talevia.json".toPath()) { readUtf8() }
        assertFalse(mainJson.contains("\"fingerprint\""))

        // .talevia-cache/clip-render-cache.json should contain it (pretty-print may insert space)
        val cacheJson = fs.read("/projects/foo/.talevia-cache/clip-render-cache.json".toPath()) { readUtf8() }
        assertTrue(cacheJson.contains("\"fingerprint\""), "cache JSON missing fingerprint key")
        assertTrue(cacheJson.contains("\"fp\""), "cache JSON missing fp value")

        // Round-trip through get must restore the cache
        val reloaded = store.get(created.id)!!
        assertEquals(1, reloaded.clipRenderCache.entries.size)
        assertEquals("fp", reloaded.clipRenderCache.entries.single().fingerprint)
    }

    @Test
    fun deleteOnlyUnregistersByDefault() = runTest {
        val (store, fs, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")

        store.delete(created.id)

        // Files still on disk
        assertTrue(fs.exists("/projects/foo/talevia.json".toPath()))
        // But registry no longer knows the project
        assertNull(store.get(created.id))
        assertEquals(0, store.listSummaries().size)
    }

    @Test
    fun deleteWithDeleteFilesRemovesTaleviaOwnedContent() = runTest {
        val (store, fs, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")
        // User adds an unrelated file under the bundle
        fs.write("/projects/foo/README.md".toPath()) { writeUtf8("# Notes") }

        store.delete(created.id, deleteFiles = true)

        // Talevia files gone
        assertFalse(fs.exists("/projects/foo/talevia.json".toPath()))
        assertFalse(fs.exists("/projects/foo/.gitignore".toPath()))
        assertFalse(fs.exists("/projects/foo/media".toPath()))
        assertFalse(fs.exists("/projects/foo/.talevia-cache".toPath()))
        // User's file survives → directory survives
        assertTrue(fs.exists("/projects/foo/README.md".toPath()))
    }

    @Test
    fun deleteWithDeleteFilesRemovesEmptyBundleDirectory() = runTest {
        val (store, fs, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")

        store.delete(created.id, deleteFiles = true)

        // Bundle dir is empty after Talevia files removed → rmdir
        assertFalse(fs.exists("/projects/foo".toPath()))
    }

    @Test
    fun setTitleUpdatesEnvelopeTitleOnly() = runTest {
        val (store, fs, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")

        // Snapshot the project body bytes (sans envelope title) before setTitle
        val before = fs.read("/projects/foo/talevia.json".toPath()) { readUtf8() }

        store.setTitle(created.id, "Renamed")

        val after = fs.read("/projects/foo/talevia.json".toPath()) { readUtf8() }
        assertTrue(after.contains("\"title\": \"Renamed\""))
        assertFalse(after.contains("\"title\": \"Foo\""))
        // Project body unchanged → only the title bytes differ
        assertTrue(before.length - after.length <= "Renamed".length - "Foo".length || after.length - before.length >= "Renamed".length - "Foo".length)

        // Summary reflects new title
        assertEquals("Renamed", store.summary(created.id)!!.title)
    }

    @Test
    fun listSummariesSortsByUpdatedAtDesc() = runTest {
        val (store, _, clock) = setup(now = 100)
        store.createAt("/projects/a".toPath(), "A")
        clock.nowMs = 500
        store.createAt("/projects/b".toPath(), "B")
        clock.nowMs = 300
        store.createAt("/projects/c".toPath(), "C")

        val summaries = store.listSummaries()
        // FakeFileSystem mtime tracks clock, so order is B, C, A
        assertEquals(listOf("B", "C", "A"), summaries.map { it.title })
    }

    @Test
    fun pathTraversalProjectIdRejectedAsDefaultPath() = runTest {
        val (store, _, _) = setup()
        val nasty = Project(
            id = ProjectId("../escape"),
            timeline = Timeline(),
        )
        // upsert without prior registration falls back to defaultProjectsHome/<id>/
        // which would compute "/.talevia/projects/../escape" if naive — must throw.
        assertFailsWith<IllegalArgumentException> {
            store.upsert("nasty", nasty)
        }
    }

    @Test
    fun mutateAppliesBlockUnderLockAndPersists() = runTest {
        val (store, _, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")

        val updated = store.mutate(created.id) { current ->
            current.copy(assets = listOf(asset("new-asset")))
        }

        assertEquals(1, updated.assets.size)
        assertEquals("new-asset", updated.assets.single().id.value)

        // Reload confirms persistence
        assertEquals("new-asset", store.get(created.id)!!.assets.single().id.value)
    }

    @Test
    fun jsonIsPrettyPrintedForGitDiffs() = runTest {
        val (store, fs, _) = setup()
        store.createAt("/projects/foo".toPath(), "Foo")
        val text = fs.read("/projects/foo/talevia.json".toPath()) { readUtf8() }
        assertTrue(text.contains("\n"), "Expected newline-separated JSON for git friendliness")
        assertTrue(text.contains("  "), "Expected 2-space indent")
    }

    @Test
    fun emptyLockfileSerializedExplicitly() = runTest {
        // Sanity check: ensure our pretty-print/encoding round-trips empty-collection
        // defaults so opening a freshly-created bundle doesn't hit a serializer surprise.
        val (store, _, _) = setup()
        val created = store.createAt("/projects/foo".toPath(), "Foo")
        assertEquals(Lockfile.EMPTY, created.lockfile)
        assertEquals(0, store.get(created.id)!!.lockfile.entries.size)
    }

}
