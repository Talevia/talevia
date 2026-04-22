package io.talevia.core.domain

import io.talevia.core.ProjectId
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentsRegistryTest {

    private fun newRegistry(): Pair<RecentsRegistry, FakeFileSystem> {
        val fs = FakeFileSystem()
        val path = "/.talevia/recents.json".toPath()
        val registry = RecentsRegistry(path, fs)
        return registry to fs
    }

    @Test
    fun listsEmptyWhenFileMissing() = runTest {
        val (registry, _) = newRegistry()
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun upsertCreatesFileAndEntry() = runTest {
        val (registry, fs) = newRegistry()
        registry.upsert(ProjectId("p1"), "/projects/foo".toPath(), "Foo", lastOpenedAtEpochMs = 1_000)

        val all = registry.list()
        assertEquals(1, all.size)
        assertEquals("p1", all.single().id)
        assertEquals("/projects/foo", all.single().path)
        assertEquals("Foo", all.single().title)
        assertEquals(1_000L, all.single().lastOpenedAtEpochMs)

        // File materialized.
        assertTrue(fs.exists("/.talevia/recents.json".toPath()))
    }

    @Test
    fun upsertSameIdNewPathReplacesEntry() = runTest {
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("p1"), "/projects/foo".toPath(), "Foo", 1_000)
        registry.upsert(ProjectId("p1"), "/elsewhere/foo".toPath(), "Foo", 2_000)

        val all = registry.list()
        assertEquals(1, all.size)
        assertEquals("/elsewhere/foo", all.single().path)
        assertEquals(2_000L, all.single().lastOpenedAtEpochMs)
    }

    @Test
    fun listSortsByLastOpenedDesc() = runTest {
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("p1"), "/a".toPath(), "A", 1_000)
        registry.upsert(ProjectId("p2"), "/b".toPath(), "B", 5_000)
        registry.upsert(ProjectId("p3"), "/c".toPath(), "C", 3_000)

        val all = registry.list()
        assertEquals(listOf("p2", "p3", "p1"), all.map { it.id })
    }

    @Test
    fun getReturnsRegisteredEntry() = runTest {
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("p1"), "/a".toPath(), "A", 1_000)

        val entry = registry.get(ProjectId("p1"))
        assertEquals("/a", entry?.path)

        assertNull(registry.get(ProjectId("missing")))
    }

    @Test
    fun removeUnregistersEntry() = runTest {
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("p1"), "/a".toPath(), "A", 1_000)
        registry.upsert(ProjectId("p2"), "/b".toPath(), "B", 2_000)

        registry.remove(ProjectId("p1"))
        assertEquals(listOf("p2"), registry.list().map { it.id })
    }

    @Test
    fun removeMissingIdIsIdempotent() = runTest {
        val (registry, _) = newRegistry()
        registry.remove(ProjectId("never-added"))
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun setTitleUpdatesTitleAndTimestamp() = runTest {
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("p1"), "/a".toPath(), "Old", 1_000)
        registry.setTitle(ProjectId("p1"), "New", updatedAtEpochMs = 9_000)

        val entry = registry.get(ProjectId("p1"))!!
        assertEquals("New", entry.title)
        assertEquals(9_000L, entry.lastOpenedAtEpochMs)
    }

    @Test
    fun corruptedRecentsFileFallsBackToEmpty() = runTest {
        val (registry, fs) = newRegistry()
        fs.createDirectories("/.talevia".toPath())
        fs.write("/.talevia/recents.json".toPath()) { writeUtf8("not json{{") }

        // Should not throw — load failure → empty.
        assertTrue(registry.list().isEmpty())

        // Subsequent upsert overwrites with valid JSON.
        registry.upsert(ProjectId("p1"), "/a".toPath(), "A", 1_000)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun staleEntriesArePreservedNotPruned() = runTest {
        // The registry doesn't know whether the path actually exists on disk;
        // pruning is the caller's job (e.g. ProjectStore.get returning null).
        val (registry, _) = newRegistry()
        registry.upsert(ProjectId("never-existed"), "/nope".toPath(), "Nope", 1_000)
        assertEquals(1, registry.list().size)
    }
}
