package io.talevia.core.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema v2 → v3: extract `Project.clipRenderCache` to its own sibling table.
 * Same legacy-fallback pattern as `SqlDelightProjectStoreSplitTest` already
 * verifies for snapshots + lockfile, narrowed to the new cache.
 *
 * Semantic boundaries this test protects (§3a rule 9):
 *  - `upsert` moves entries blob → sibling table and empties the blob field.
 *  - `get` reassembles `clipRenderCache` from the sibling table.
 *  - Legacy blob (pre-v3 inline entries, table empty) still reads correctly.
 *  - First `upsert` after a legacy read migrates: blob emptied, rows appear.
 *  - `delete` cascades through the sibling table (no orphan rows).
 *  - Repeat-fingerprint upsert (the "same clip, re-render after a cache purge"
 *    case) collapses to one row instead of stacking dup rows — guards against
 *    unbounded row growth on thrash.
 *  - Shrinking `clipRenderCache` via a GC-style mutate actually removes rows.
 */
class SqlDelightProjectStoreClipRenderCacheTest {

    private fun rig(): Pair<TaleviaDb, SqlDelightProjectStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        return db to SqlDelightProjectStore(db)
    }

    private fun entry(
        fingerprint: String,
        path: String = "/tmp/mez-$fingerprint.mp4",
        createdAt: Long = 1_700_000_000_000L,
    ): ClipRenderCacheEntry = ClipRenderCacheEntry(
        fingerprint = fingerprint,
        mezzaninePath = path,
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        durationSeconds = 4.0,
        createdAtEpochMs = createdAt,
    )

    @Test fun upsertPersistsClipCacheEntriesAndEmptiesBlob() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-cache")
        val project = Project(
            id = pid,
            timeline = Timeline(),
            clipRenderCache = ClipRenderCache(
                entries = listOf(entry("fp-1"), entry("fp-2")),
            ),
        )

        store.upsert("demo", project)

        assertEquals(2L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())

        // Blob must be cleared of the cache entries — otherwise re-encoding still
        // drags them along and the split doesn't actually cut write amplification.
        val row = db.projectsQueries.selectById(pid.value).executeAsOne()
        val blob = JsonConfig.default.decodeFromString(Project.serializer(), row.data_)
        assertTrue(blob.clipRenderCache.entries.isEmpty())
    }

    @Test fun getReassemblesClipRenderCacheFromTable() = runTest {
        val (_, store) = rig()
        val pid = ProjectId("p-cache")
        val e1 = entry("fp-1", createdAt = 1_000L)
        val e2 = entry("fp-2", createdAt = 2_000L)
        store.upsert("demo", Project(id = pid, timeline = Timeline(), clipRenderCache = ClipRenderCache(listOf(e1, e2))))

        val loaded = store.get(pid)
        assertNotNull(loaded)
        assertEquals(listOf(e1, e2), loaded!!.clipRenderCache.entries)
    }

    @Test fun legacyBlobWithInlineClipCacheReadsCorrectly() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-legacy")
        val legacy = Project(
            id = pid,
            timeline = Timeline(),
            clipRenderCache = ClipRenderCache(listOf(entry("legacy-fp"))),
        )
        // Simulate a pre-v3 row: upsert through the store would empty the blob,
        // so write the blob directly instead to keep inline entries intact.
        val now = 1L
        db.projectsQueries.upsert(
            id = pid.value,
            title = "legacy",
            data_ = JsonConfig.default.encodeToString(Project.serializer(), legacy),
            time_created = now,
            time_updated = now,
        )
        assertEquals(0L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())

        val loaded = store.get(pid)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.clipRenderCache.entries.size)
        assertEquals("legacy-fp", loaded.clipRenderCache.entries.first().fingerprint)
    }

    @Test fun firstUpsertAfterLegacyReadMigratesToTable() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-legacy-migrate")
        val legacy = Project(
            id = pid,
            timeline = Timeline(),
            clipRenderCache = ClipRenderCache(listOf(entry("legacy-fp"))),
        )
        val now = 1L
        db.projectsQueries.upsert(
            id = pid.value,
            title = "legacy",
            data_ = JsonConfig.default.encodeToString(Project.serializer(), legacy),
            time_created = now,
            time_updated = now,
        )

        val loaded = store.get(pid)!!
        // Legacy read: blob still has the entries, table still empty.
        store.upsert("legacy", loaded)

        // After the first upsert: blob emptied, table populated.
        val blob = JsonConfig.default.decodeFromString(
            Project.serializer(),
            db.projectsQueries.selectById(pid.value).executeAsOne().data_,
        )
        assertTrue(blob.clipRenderCache.entries.isEmpty())
        assertEquals(1L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())
    }

    @Test fun deleteRemovesClipRenderCacheRows() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-delete")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                clipRenderCache = ClipRenderCache(listOf(entry("fp-1"), entry("fp-2"))),
            ),
        )
        assertEquals(2L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())

        store.delete(pid)

        assertEquals(0L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())
    }

    @Test fun duplicateFingerprintUpsertCollapsesToOneRow() = runTest {
        // When a cache-miss render is thrashed (e.g. user deletes the mezzanine
        // directory between exports), the same fingerprint is written twice with
        // a different mezzanine path / timestamp. Guard: rows must NOT stack —
        // PRIMARY KEY(project_id, fingerprint) + INSERT OR REPLACE collapse them.
        val (db, store) = rig()
        val pid = ProjectId("p-dedup")
        val first = entry("fp-x", path = "/tmp/mez-1.mp4", createdAt = 1_000L)
        val second = entry("fp-x", path = "/tmp/mez-2.mp4", createdAt = 2_000L)

        store.upsert("demo", Project(id = pid, timeline = Timeline(), clipRenderCache = ClipRenderCache(listOf(first))))
        assertEquals(1L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())

        // Simulate ExportTool.append putting a second entry with the same fingerprint:
        // ClipRenderCache.append always adds to the list, so the in-memory cache
        // carries both — but the persistence layer must collapse.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                clipRenderCache = ClipRenderCache(listOf(first, second)),
            ),
        )

        assertEquals(1L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())
        // The retained row is the latest one: upsert's iteration order feeds INSERT OR REPLACE
        // so the final row wins.
        val reassembled = store.get(pid)!!
        assertEquals(1, reassembled.clipRenderCache.entries.size)
        assertEquals("/tmp/mez-2.mp4", reassembled.clipRenderCache.entries.first().mezzaninePath)
    }

    @Test fun shrinkingClipCacheRemovesRowsFromTable() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-shrink")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                clipRenderCache = ClipRenderCache(listOf(entry("fp-1"), entry("fp-2"), entry("fp-3"))),
            ),
        )
        assertEquals(3L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())

        // A future gc tool might shrink the in-memory cache back to 1 entry.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                clipRenderCache = ClipRenderCache(listOf(entry("fp-2"))),
            ),
        )

        assertEquals(1L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())
        assertEquals(
            listOf("fp-2"),
            store.get(pid)!!.clipRenderCache.entries.map { it.fingerprint },
        )
    }

    @Test fun emptyClipRenderCacheRoundTripsCleanly() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-empty")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
        assertEquals(0L, db.projectClipRenderCacheQueries.countByProject(pid.value).executeAsOne())
        assertEquals(0, store.get(pid)!!.clipRenderCache.entries.size)
    }
}
