package io.talevia.core.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the v2 split:
 *  - upsert writes snapshots/lockfile.entries to sibling tables and empties the blob.
 *  - get reconstructs the full Project from blob + tables.
 *  - legacy v1 blobs with inline snapshots/lockfile still round-trip (read-only fallback).
 *  - the first upsert after a legacy read migrates the data (blob emptied, tables filled).
 *  - delete removes rows from both sibling tables (no orphans).
 *  - Schema.version is 2.
 */
class SqlDelightProjectStoreSplitTest {

    private fun rig(): Pair<TaleviaDb, SqlDelightProjectStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        return db to SqlDelightProjectStore(db)
    }

    private fun lockfileEntry(
        inputHash: String,
        assetId: String,
        pinned: Boolean = false,
    ): LockfileEntry = LockfileEntry(
        inputHash = inputHash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L,
        ),
        pinned = pinned,
    )

    private fun snapshot(id: String, label: String, capturedAt: Long): ProjectSnapshot =
        ProjectSnapshot(
            id = ProjectSnapshotId(id),
            label = label,
            capturedAtEpochMs = capturedAt,
            project = Project(id = ProjectId("inner"), timeline = Timeline()),
        )

    // ── schema version ────────────────────────────────────────────────

    @Test fun schemaVersionIsTwo() {
        assertEquals(2L, TaleviaDb.Schema.version)
    }

    // ── round-trip ────────────────────────────────────────────────────

    @Test fun upsertPersistsSnapshotsAndLockfileEntriesAndEmptiesBlob() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-1")
        val project = Project(
            id = pid,
            timeline = Timeline(),
            lockfile = Lockfile(
                entries = listOf(
                    lockfileEntry("h-1", "a-1"),
                    lockfileEntry("h-2", "a-2", pinned = true),
                ),
            ),
            snapshots = listOf(
                snapshot("snap-1", "v1", 1_000L),
                snapshot("snap-2", "v2", 2_000L),
            ),
        )

        store.upsert("demo", project)

        assertEquals(2L, db.projectSnapshotsQueries.countByProject(pid.value).executeAsOne())
        assertEquals(2L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())

        // The blob on disk must have emptied the two append-only lists — otherwise the
        // split gains nothing because re-encode still writes them.
        val row = db.projectsQueries.selectById(pid.value).executeAsOne()
        val decodedBlob = JsonConfig.default.decodeFromString(Project.serializer(), row.data_)
        assertTrue(decodedBlob.snapshots.isEmpty())
        assertTrue(decodedBlob.lockfile.entries.isEmpty())
    }

    @Test fun getReassemblesFullProjectFromBlobAndTables() = runTest {
        val (_, store) = rig()
        val pid = ProjectId("p-1")
        val project = Project(
            id = pid,
            timeline = Timeline(
                tracks = listOf(Track.Video(id = TrackId("v"), clips = emptyList())),
                duration = 10.seconds,
            ),
            lockfile = Lockfile(
                entries = listOf(
                    lockfileEntry("h-1", "a-1"),
                    lockfileEntry("h-2", "a-2", pinned = true),
                ),
            ),
            snapshots = listOf(
                snapshot("snap-1", "v1", 1_000L),
                snapshot("snap-2", "v2", 2_000L),
            ),
        )

        store.upsert("demo", project)
        val reassembled = store.get(pid)
        assertNotNull(reassembled)
        assertEquals(project.snapshots.map { it.id }, reassembled!!.snapshots.map { it.id })
        assertEquals(project.lockfile.entries.map { it.inputHash }, reassembled.lockfile.entries.map { it.inputHash })
        // Field-for-field match after clearing `updatedAtEpochMs` recency stamps
        // (which upsert adds — covered in ProjectStoreRecencyStampingTest).
        val clearedInput = project.copy(
            timeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { (it as Track.Video).copy(updatedAtEpochMs = null) },
            ),
        )
        val clearedOutput = reassembled.copy(
            timeline = reassembled.timeline.copy(
                tracks = reassembled.timeline.tracks.map { (it as Track.Video).copy(updatedAtEpochMs = null) },
            ),
        )
        assertEquals(clearedInput, clearedOutput)
    }

    @Test fun lockfileOrdinalPreservesAppendOrderAcrossUpserts() = runTest {
        val (_, store) = rig()
        val pid = ProjectId("p-append")
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(), lockfile = Lockfile(listOf(lockfileEntry("h-1", "a-1")))),
        )
        // Second upsert appends a new entry. `Lockfile.findByInputHash` semantics:
        // "most recent match wins". Ordinal must preserve that so a sibling-table
        // read with ORDER BY ordinal ASC yields the same order as the in-memory list.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                lockfile = Lockfile(
                    listOf(
                        lockfileEntry("h-1", "a-1"),
                        lockfileEntry("h-1", "a-1-regen"),
                    ),
                ),
            ),
        )
        val reassembled = store.get(pid)!!
        assertEquals(2, reassembled.lockfile.entries.size)
        // findByInputHash reports the most-recent match — verifies ordinal-driven ordering.
        val found = reassembled.lockfile.findByInputHash("h-1")
        assertEquals(AssetId("a-1-regen"), found!!.assetId)
    }

    // ── legacy fallback ───────────────────────────────────────────────

    @Test fun legacyBlobWithInlineSnapshotsAndLockfileReadsCorrectly() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-legacy")
        val fullProject = Project(
            id = pid,
            timeline = Timeline(),
            lockfile = Lockfile(listOf(lockfileEntry("h-legacy", "a-legacy"))),
            snapshots = listOf(snapshot("snap-legacy", "legacy", 100L)),
        )
        // Write the *full* Project (including snapshots + lockfile) directly to the
        // Projects.data blob — this simulates a v1 row that was never touched after
        // the v2 upgrade. The sibling tables stay empty.
        val legacyBlob = JsonConfig.default.encodeToString(Project.serializer(), fullProject)
        db.projectsQueries.upsert(
            id = pid.value,
            title = "legacy",
            data_ = legacyBlob,
            time_created = 0L,
            time_updated = 0L,
        )
        assertEquals(0L, db.projectSnapshotsQueries.countByProject(pid.value).executeAsOne())
        assertEquals(0L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())

        val reassembled = store.get(pid)!!
        assertEquals(fullProject.snapshots, reassembled.snapshots)
        assertEquals(fullProject.lockfile.entries, reassembled.lockfile.entries)
    }

    @Test fun firstUpsertAfterLegacyReadMigratesToTables() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-migrate")
        val fullProject = Project(
            id = pid,
            timeline = Timeline(),
            lockfile = Lockfile(listOf(lockfileEntry("h-old", "a-old"))),
            snapshots = listOf(snapshot("snap-old", "old", 50L)),
        )
        db.projectsQueries.upsert(
            id = pid.value,
            title = "legacy",
            data_ = JsonConfig.default.encodeToString(Project.serializer(), fullProject),
            time_created = 0L,
            time_updated = 0L,
        )

        // Read via store — hits the legacy fallback path.
        val readBack = store.get(pid)!!
        // Write the same thing straight back: simulates "any subsequent mutate".
        store.upsert("legacy", readBack)

        // Sibling tables now populated …
        assertEquals(1L, db.projectSnapshotsQueries.countByProject(pid.value).executeAsOne())
        assertEquals(1L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())
        // … and blob lists are empty.
        val rowAfter = db.projectsQueries.selectById(pid.value).executeAsOne()
        val blobAfter = JsonConfig.default.decodeFromString(Project.serializer(), rowAfter.data_)
        assertTrue(blobAfter.snapshots.isEmpty())
        assertTrue(blobAfter.lockfile.entries.isEmpty())
    }

    // ── delete cascade + empty semantics ─────────────────────────────

    @Test fun deleteRemovesRowsFromSiblingTables() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-del")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                lockfile = Lockfile(listOf(lockfileEntry("h", "a"))),
                snapshots = listOf(snapshot("s", "x", 1L)),
            ),
        )

        store.delete(pid)

        assertNull(db.projectsQueries.selectById(pid.value).executeAsOneOrNull())
        assertEquals(0L, db.projectSnapshotsQueries.countByProject(pid.value).executeAsOne())
        assertEquals(0L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())
    }

    @Test fun emptySnapshotsAndLockfileRoundTripCleanly() = runTest {
        val (db, store) = rig()
        val pid = ProjectId("p-empty")
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline()),
        )
        assertEquals(0L, db.projectSnapshotsQueries.countByProject(pid.value).executeAsOne())
        assertEquals(0L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())

        val reassembled = store.get(pid)!!
        assertTrue(reassembled.snapshots.isEmpty())
        assertTrue(reassembled.lockfile.entries.isEmpty())
    }

    @Test fun shrinkingLockfileRemovesRowsFromTable() = runTest {
        // Important: upsert is delete-all + re-insert. Verify that trimming the
        // in-memory list actually shrinks the table — without this the split would
        // leak old rows forever.
        val (db, store) = rig()
        val pid = ProjectId("p-shrink")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                lockfile = Lockfile(
                    listOf(
                        lockfileEntry("h-1", "a-1"),
                        lockfileEntry("h-2", "a-2"),
                        lockfileEntry("h-3", "a-3"),
                    ),
                ),
            ),
        )
        assertEquals(3L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())

        // Now simulate a gc_lockfile sweep that dropped two entries.
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(),
                lockfile = Lockfile(listOf(lockfileEntry("h-3", "a-3"))),
            ),
        )
        assertEquals(1L, db.projectLockfileEntriesQueries.countByProject(pid.value).executeAsOne())
        val reassembled = store.get(pid)!!
        assertEquals(listOf("h-3"), reassembled.lockfile.entries.map { it.inputHash })
    }

    @Test fun listReturnsAllProjectsWithAssembledSiblings() = runTest {
        val (_, store) = rig()
        store.upsert(
            "p1",
            Project(
                id = ProjectId("p-1"),
                timeline = Timeline(),
                lockfile = Lockfile(listOf(lockfileEntry("h-1", "a-1"))),
            ),
        )
        store.upsert(
            "p2",
            Project(
                id = ProjectId("p-2"),
                timeline = Timeline(),
                snapshots = listOf(snapshot("s", "x", 1L)),
            ),
        )

        val all = store.list().associateBy { it.id.value }
        assertEquals(1, all["p-1"]!!.lockfile.entries.size)
        assertEquals(0, all["p-1"]!!.snapshots.size)
        assertEquals(0, all["p-2"]!!.lockfile.entries.size)
        assertEquals(1, all["p-2"]!!.snapshots.size)
    }
}
