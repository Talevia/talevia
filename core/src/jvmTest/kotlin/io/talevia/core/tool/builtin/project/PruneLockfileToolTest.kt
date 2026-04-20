package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PruneLockfileToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
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

    private fun fakeProvenance(seed: Long = 1L, createdAt: Long = 1_700_000_000_000L): GenerationProvenance =
        GenerationProvenance(
            providerId = "fake",
            modelId = "fake-model",
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAt,
        )

    private fun fakeAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.png"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun entry(
        toolId: String,
        assetId: String,
        seed: Long = 1L,
        createdAt: Long = 1_700_000_000_000L,
    ): LockfileEntry = LockfileEntry(
        inputHash = "h-$assetId",
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = fakeProvenance(seed = seed, createdAt = createdAt),
    )

    private suspend fun seed(
        rig: Rig,
        projectId: String = "p",
        assetIds: List<String> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ) {
        var lockfile = Lockfile.EMPTY
        entries.forEach { lockfile = lockfile.append(it) }
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId(projectId),
                timeline = Timeline(),
                assets = assetIds.map { fakeAsset(it) },
                lockfile = lockfile,
            ),
        )
    }

    @Test fun emptyLockfileIsNoOp() = runTest {
        val rig = rig()
        seed(rig, assetIds = listOf("a-1"))

        val out = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(0, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertTrue(out.data.prunedEntries.isEmpty())
        assertEquals(false, out.data.dryRun)

        // Store unchanged.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.entries.isEmpty())
    }

    @Test fun allKeptCaseLeavesStoreIntact() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1"),
            entry("generate_image", "a-2"),
            entry("synthesize_speech", "a-3"),
        )
        seed(rig, assetIds = listOf("a-1", "a-2", "a-3"), entries = entries)

        val out = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(3, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertEquals(3, out.data.keptCount)
        assertTrue(out.data.prunedEntries.isEmpty())

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(3, refreshed.lockfile.entries.size)
        assertEquals(
            listOf("a-1", "a-2", "a-3"),
            refreshed.lockfile.entries.map { it.assetId.value },
        )
    }

    @Test fun allOrphanCasePrunesEverything() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "gone-1"),
            entry("generate_image", "gone-2"),
        )
        // No assets in project — every entry is an orphan.
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(2, out.data.totalEntries)
        assertEquals(2, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertEquals(
            setOf("gone-1", "gone-2"),
            out.data.prunedEntries.map { it.assetId }.toSet(),
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.entries.isEmpty())
    }

    @Test fun mixedCaseKeepsReferencedPrunesOrphansAndPreservesKeptOrder() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1", createdAt = 1_000L),
            entry("generate_image", "gone-1", createdAt = 2_000L),
            entry("synthesize_speech", "a-2", createdAt = 3_000L),
            entry("generate_image", "gone-2", createdAt = 4_000L),
            entry("generate_image", "a-3", createdAt = 5_000L),
        )
        seed(rig, assetIds = listOf("a-1", "a-2", "a-3"), entries = entries)

        val out = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(5, out.data.totalEntries)
        assertEquals(2, out.data.prunedCount)
        assertEquals(3, out.data.keptCount)
        assertEquals(
            setOf("gone-1", "gone-2"),
            out.data.prunedEntries.map { it.assetId }.toSet(),
        )
        // Pruned summaries expose inputHash + toolId + assetId so the agent
        // can phrase the dropped rows.
        val goneOne = out.data.prunedEntries.single { it.assetId == "gone-1" }
        assertEquals("h-gone-1", goneOne.inputHash)
        assertEquals("generate_image", goneOne.toolId)

        // Kept order is preserved (append-only insertion order).
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            listOf("a-1", "a-2", "a-3"),
            refreshed.lockfile.entries.map { it.assetId.value },
        )
    }

    @Test fun dryRunReportsCountsWithoutMutating() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1"),
            entry("generate_image", "gone-1"),
            entry("generate_image", "gone-2"),
        )
        seed(rig, assetIds = listOf("a-1"), entries = entries)

        val out = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p", dryRun = true),
            rig.ctx,
        )

        assertEquals(true, out.data.dryRun)
        assertEquals(3, out.data.totalEntries)
        assertEquals(2, out.data.prunedCount)
        assertEquals(1, out.data.keptCount)
        assertEquals(
            setOf("gone-1", "gone-2"),
            out.data.prunedEntries.map { it.assetId }.toSet(),
        )

        // Store still has the original three entries — dry run is preview only.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(3, refreshed.lockfile.entries.size)
        assertEquals(
            listOf("a-1", "gone-1", "gone-2"),
            refreshed.lockfile.entries.map { it.assetId.value },
        )
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            PruneLockfileTool(rig.store).execute(
                PruneLockfileTool.Input(projectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun idempotentAcrossTwoCalls() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1"),
            entry("generate_image", "gone-1"),
            entry("generate_image", "a-2"),
            entry("generate_image", "gone-2"),
        )
        seed(rig, assetIds = listOf("a-1", "a-2"), entries = entries)

        val first = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )
        assertEquals(2, first.data.prunedCount)
        assertEquals(2, first.data.keptCount)

        val second = PruneLockfileTool(rig.store).execute(
            PruneLockfileTool.Input(projectId = "p"),
            rig.ctx,
        )
        // Second call is a no-op over the already-pruned state.
        assertEquals(2, second.data.totalEntries)
        assertEquals(0, second.data.prunedCount)
        assertEquals(2, second.data.keptCount)
        assertTrue(second.data.prunedEntries.isEmpty())

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            listOf("a-1", "a-2"),
            refreshed.lockfile.entries.map { it.assetId.value },
        )
    }
}
