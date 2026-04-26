package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // --- `.talevia` bundle-extension convention (bundle-mac-launch-services cycle) ---

    @Test fun openAtAcceptsPathWithTaleviaExtension() = runTest {
        val (store, _, _) = setup()
        val created = store.createAt("/projects/demo.talevia".toPath(), "Demo")
        val reopened = store.openAt("/projects/demo.talevia".toPath())
        assertEquals(created.id, reopened.id)
    }

    @Test fun openAtAcceptsBarePathWhenBundleDirIsBare() = runTest {
        val (store, _, _) = setup()
        val created = store.createAt("/projects/plain".toPath(), "Plain")
        val reopened = store.openAt("/projects/plain".toPath())
        assertEquals(created.id, reopened.id)
    }

    @Test fun openAtAutoPromotesBarePathWhenDotTaleviaVariantExists() = runTest {
        val (store, _, _) = setup()
        val created = store.createAt("/projects/demo.talevia".toPath(), "Demo")
        // Caller passes the bare name; the on-disk directory has the .talevia suffix.
        val reopened = store.openAt("/projects/demo".toPath())
        assertEquals(created.id, reopened.id, "openAt should auto-promote /projects/demo → /projects/demo.talevia")
    }

    @Test fun openAtPrefersBareWhenBothVariantsExist() = runTest {
        val (store, _, _) = setup()
        val bare = store.createAt("/projects/demo".toPath(), "Bare")
        val suffixed = store.createAt("/projects/demo.talevia".toPath(), "Suffixed")
        // Caller asked for the bare path; bare exists, so use it verbatim.
        val reopened = store.openAt("/projects/demo".toPath())
        assertEquals(bare.id, reopened.id)
        // And the suffixed one is still reachable by its own exact path.
        assertEquals(suffixed.id, store.openAt("/projects/demo.talevia".toPath()).id)
    }

    @Test fun openAtDoesNotStripExtensionWhenBarePathExists() = runTest {
        // Reverse: only /projects/demo exists on disk; caller asks for demo.talevia
        // which doesn't exist. Store must NOT silently fall back to /projects/demo —
        // the extension is a distinct directory the caller explicitly named.
        val (store, _, _) = setup()
        store.createAt("/projects/demo".toPath(), "Bare")
        // Any exception type is fine — the point is it MUST fail, not silently
        // fall back to the bare directory. okio / Json throw different concrete
        // types across platforms; assertFails swallows the specific class.
        try {
            store.openAt("/projects/demo.talevia".toPath())
            throw AssertionError("openAt on /projects/demo.talevia should have failed (no such bundle)")
        } catch (e: AssertionError) {
            throw e
        } catch (_: Throwable) {
            // Expected — store refused, did not silently fall back.
        }
    }

    // -------------------------------------------------------------------------
    // openAt runtime edge cases — R.5 #10 critical-path coverage
    // (`debt-runtime-test-fileproject-store-openat`, cycle 162). Existing
    // tests above cover happy-path open + missing-file refusal + path-shape
    // resolution; these four pin schemaVersion compatibility behaviour,
    // corrupted-envelope refusal shape, and the read-only auto-create
    // contract that openAt must honour (auto-create lives on createAt /
    // upsert, not openAt).
    // -------------------------------------------------------------------------

    @Test fun openAtAcceptsLegacySchemaVersionZero() = runTest {
        // Bundles that pre-date the [StoredProject.schemaVersion] field
        // decode with `schemaVersion=0` (the kotlinx-serialization default
        // when an explicit `schemaVersion=0` is written, or when the field
        // is absent in legacy JSON and `JsonConfig.default.coerceInputValues`
        // backfills the constructor default). There's no formal migration
        // framework today — this test pins the "legacy survives" invariant
        // so a future schema bump that introduces real migration logic must
        // explicitly update this assertion to reflect the new behaviour.
        val (store, fs, _) = setup()
        val bundle = "/projects/legacy".toPath()
        fs.createDirectories(bundle)
        fs.write(bundle.resolve("talevia.json")) {
            writeUtf8(
                """{"schemaVersion":0,"title":"legacy","createdAtEpochMs":0,""" +
                    """"project":{"id":"p-legacy","timeline":{"tracks":[]}}}""",
            )
        }
        val project = store.openAt(bundle)
        assertEquals(ProjectId("p-legacy"), project.id)
    }

    @Test fun openAtAcceptsForwardSchemaVersionAsNoOp() = runTest {
        // A hypothetical future bundle (schemaVersion=99) decodes today
        // because no version-too-new gate is wired. This is a deliberate
        // current-state pin: if a future cycle adds a version-refused gate
        // for forward compat (e.g. "this binary is built against
        // schemaVersion ≤ 1; refuse 2+ to avoid silently lossy reads"),
        // this test must flip to assertFailsWith so the regression is
        // explicit. Until then, "we silently accept" is the documented
        // contract.
        val (store, fs, _) = setup()
        val bundle = "/projects/future".toPath()
        fs.createDirectories(bundle)
        fs.write(bundle.resolve("talevia.json")) {
            writeUtf8(
                """{"schemaVersion":99,"title":"future","createdAtEpochMs":0,""" +
                    """"project":{"id":"p-future","timeline":{"tracks":[]}}}""",
            )
        }
        val project = store.openAt(bundle)
        assertEquals(ProjectId("p-future"), project.id)
    }

    @Test fun openAtFailsLoudlyOnCorruptedTaleviaJson() = runTest {
        // Truncated / malformed envelope must fail with an exception, not
        // crash silently or return an empty project. kotlinx-serialization
        // surfaces `SerializationException` on bad input; the concrete
        // subtype is platform-dependent (JVM throws
        // `MissingFieldException` on absent required fields, JS / Native
        // can vary), so the assertion targets the general case via
        // `assertFails` semantics — if a regression makes openAt swallow
        // the error and return a default Project, the test fails because
        // no exception is thrown.
        val (store, fs, _) = setup()
        val bundle = "/projects/corrupted".toPath()
        fs.createDirectories(bundle)
        fs.write(bundle.resolve("talevia.json")) {
            // Truncate mid-object — unclosed brace, missing required `project` value.
            writeUtf8("""{"schemaVersion":1,"title":"corrupted","project":""")
        }
        var threw = false
        try {
            store.openAt(bundle)
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue(threw, "openAt must fail loudly on corrupted talevia.json, not silently return a default Project")
    }

    @Test fun openAtDoesNotAutoCreateGitignoreOrMediaDir() = runTest {
        // `openAt` is a read-only entry point — auto-creation of `.gitignore`
        // and `media/` is reserved for `createAt` / `upsert` (the
        // `writeBundleLocked` path). A bundle imported from elsewhere may
        // legitimately not have these directories; openAt must not modify
        // the on-disk state behind the user's back. Pins this so a future
        // "ergonomic" change that auto-fills missing dirs on read trips
        // the test and forces the change to be explicit.
        val (store, fs, _) = setup()
        val bundle = "/projects/bare".toPath()
        fs.createDirectories(bundle)
        fs.write(bundle.resolve("talevia.json")) {
            writeUtf8(
                """{"schemaVersion":1,"title":"bare","createdAtEpochMs":0,""" +
                    """"project":{"id":"p-bare","timeline":{"tracks":[]}}}""",
            )
        }
        store.openAt(bundle)
        assertFalse(
            fs.exists(bundle.resolve(".gitignore")),
            "openAt must not auto-create .gitignore (auto-create is reserved for createAt / upsert)",
        )
        assertFalse(
            fs.exists(bundle.resolve("media")),
            "openAt must not auto-create media/ (auto-create is reserved for createAt / upsert)",
        )
    }
}
