package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.LockfileDiffRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `select=lockfile_diff` on [ProjectQueryTool] — the cache-health
 * delta the agent reaches for after `regenerate_stale_clips` to know how
 * many entries were re-cached vs reused.
 */
class ProjectQueryLockfileDiffTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private fun fakeAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.png"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun entry(
        hash: String,
        toolId: String = "generate_image",
        assetId: String = "a-$hash",
        modelId: String = "fake-model",
        createdAt: Long = NOW_MS,
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = modelId,
            modelVersion = null,
            seed = 1L,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAt,
        ),
    )

    private fun project(
        id: String = "p",
        entries: List<LockfileEntry> = emptyList(),
        snapshots: List<ProjectSnapshot> = emptyList(),
    ): Project {
        var lockfile: Lockfile = EagerLockfile()
        entries.forEach { lockfile = lockfile.append(it) }
        return Project(
            id = ProjectId(id),
            timeline = Timeline(),
            assets = entries.map { fakeAsset(it.assetId.value) },
            lockfile = lockfile,
            snapshots = snapshots,
        )
    }

    private suspend fun runDiff(rig: Rig, projectId: String, fromSnap: String?, toSnap: String?): LockfileDiffRow {
        val out = ProjectQueryTool(rig.store).execute(
            ProjectQueryTool.Input(
                projectId = projectId,
                select = "lockfile_diff",
                fromSnapshotId = fromSnap,
                toSnapshotId = toSnap,
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.total)
        assertEquals(1, out.returned)
        val rows = out.rows.decodeRowsAs(LockfileDiffRow.serializer())
        return rows.single()
    }

    @Test fun snapshotVsCurrentDetectsAddedEntries() = runTest {
        // Realistic scenario: regenerate_stale_clips ran between snapshot
        // and current, appending two new entries while the original three
        // stayed cached.
        val rig = rig()
        val v1Entries = listOf(entry("h1"), entry("h2"), entry("h3"))
        val v2Entries = v1Entries + entry("h4") + entry("h5")
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project(entries = v1Entries),
        )
        rig.store.upsert("demo", project(entries = v2Entries, snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        assertFalse(diff.identical)
        assertEquals(2, diff.totalChanges)
        assertEquals(setOf("h4", "h5"), diff.added.map { it.inputHash }.toSet())
        assertTrue(diff.removed.isEmpty())
        assertEquals(3, diff.unchangedCount)
        assertTrue(diff.fromLabel.contains("v1"))
        assertTrue(diff.toLabel.contains("current"))
    }

    @Test fun detectsRemovedEntries() = runTest {
        // gc_lockfile_orphans removed two entries between snapshots.
        val rig = rig()
        val v1Entries = listOf(entry("h1"), entry("h2"), entry("h3"))
        val v2Entries = listOf(entry("h1"))
        val snap1 = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project(entries = v1Entries),
        )
        val snap2 = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v2"),
            label = "v2",
            capturedAtEpochMs = 2_000L,
            project = project(entries = v2Entries),
        )
        rig.store.upsert("demo", project(snapshots = listOf(snap1, snap2)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = "snap-v2")
        assertEquals(setOf("h2", "h3"), diff.removed.map { it.inputHash }.toSet())
        assertTrue(diff.added.isEmpty())
        assertEquals(1, diff.unchangedCount)
        assertEquals(2, diff.totalChanges)
    }

    @Test fun identicalLockfileReportsZeroChanges() = runTest {
        val rig = rig()
        val entries = listOf(entry("h1"), entry("h2"))
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project(entries = entries),
        )
        rig.store.upsert("demo", project(entries = entries, snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        assertTrue(diff.identical)
        assertEquals(0, diff.totalChanges)
        assertEquals(2, diff.unchangedCount)
    }

    @Test fun bothNullFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", project())
        val ex = assertFailsWith<IllegalArgumentException> {
            runDiff(rig, "p", fromSnap = null, toSnap = null)
        }
        // The error must explicitly name the constraint so the agent can
        // self-correct without having to re-read the schema.
        assertTrue(ex.message!!.contains("fromSnapshotId") || ex.message!!.contains("toSnapshotId"), ex.message)
    }

    @Test fun unknownSnapshotFailsLoud() = runTest {
        val rig = rig()
        rig.store.upsert("demo", project())
        val ex = assertFailsWith<IllegalStateException> {
            runDiff(rig, "p", fromSnap = "ghost", toSnap = null)
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("project_query(select=snapshots)"), "should hint at the list route")
    }

    @Test fun snapshotFiltersStillRejectedOnTimelineClips() = runTest {
        // Filter-guard widening must not silently accept snapshot ids on
        // unrelated selects. timeline_clips still rejects them.
        val rig = rig()
        rig.store.upsert("demo", project())
        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(rig.store).execute(
                ProjectQueryTool.Input(
                    projectId = "p",
                    select = "timeline_clips",
                    fromSnapshotId = "snap-v1",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("fromSnapshotId"), ex.message)
        // The error message itself should advertise both valid selects so
        // the agent doesn't have to grep the schema to fix it.
        assertTrue(ex.message!!.contains("lockfile_diff"), "guard hint should mention lockfile_diff")
    }

    @Test fun rowsCarryProvenanceFields() = runTest {
        // Each detail entry on `added`/`removed` must echo provider/model
        // so the agent can reason about cache provenance without a follow-
        // up `lockfile_entry` lookup per hash.
        val rig = rig()
        val newEntry = entry("h-new", toolId = "generate_video", modelId = "veo-3", createdAt = 99_999L)
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("snap-v1"),
            label = "v1",
            capturedAtEpochMs = 1_000L,
            project = project(entries = emptyList()),
        )
        rig.store.upsert("demo", project(entries = listOf(newEntry), snapshots = listOf(snap)))

        val diff = runDiff(rig, "p", fromSnap = "snap-v1", toSnap = null)
        val ref = diff.added.single()
        assertEquals("h-new", ref.inputHash)
        assertEquals("generate_video", ref.toolId)
        assertEquals("a-h-new", ref.assetId)
        assertEquals("fake", ref.providerId)
        assertEquals("veo-3", ref.modelId)
        assertEquals(99_999L, ref.createdAtEpochMs)
    }

    private companion object {
        const val NOW_MS: Long = 1_700_000_000_000L
    }
}
