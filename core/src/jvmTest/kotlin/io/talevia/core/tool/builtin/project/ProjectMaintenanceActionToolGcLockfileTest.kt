package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProjectMaintenanceActionToolGcLockfileTest {

    private companion object {
        const val NOW_MS: Long = 1_700_000_000_000L
        const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW_MS)
    }

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

    private fun fakeProvenance(seed: Long = 1L, createdAt: Long = NOW_MS): GenerationProvenance =
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
        createdAt: Long = NOW_MS,
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

    @Test fun ageOnlyPolicyDropsOlderThanThresholdKeepsEqual() = runTest {
        val rig = rig()
        // maxAgeDays = 7 → cutoff = NOW - 7d. Entries strictly older drop; equal keeps.
        val entries = listOf(
            entry("generate_image", "fresh", createdAt = NOW_MS),
            entry("generate_image", "boundary", createdAt = NOW_MS - 7L * MS_PER_DAY),
            entry("generate_image", "just-past", createdAt = NOW_MS - 7L * MS_PER_DAY - 1),
            entry("generate_image", "ancient", createdAt = NOW_MS - 30L * MS_PER_DAY),
        )
        // No assets in the project so the live-asset guard never rescues any row.
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", maxAgeDays = 7, preserveLiveAssets = false),
            rig.ctx,
        )

        assertEquals(4, out.data.totalEntries)
        assertEquals(2, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(
            setOf("just-past", "ancient"),
            out.data.prunedGcLockfileRows.map { it.assetId }.toSet(),
        )
        out.data.prunedGcLockfileRows.forEach { assertEquals("age", it.reason) }

        // Boundary row is preserved.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("fresh", "boundary"),
            refreshed.lockfile.entries.map { it.assetId.value }.toSet(),
        )
    }

    @Test fun countOnlyPolicyKeepsMostRecentNPerToolId() = runTest {
        val rig = rig()
        // Two toolIds, 4 entries each, keep 2 most-recent per tool.
        val entries = listOf(
            // generate_image bucket: t=1..4, drop 1 & 2, keep 3 & 4.
            entry("generate_image", "gi-1", createdAt = NOW_MS - 40_000),
            entry("generate_image", "gi-2", createdAt = NOW_MS - 30_000),
            entry("generate_image", "gi-3", createdAt = NOW_MS - 20_000),
            entry("generate_image", "gi-4", createdAt = NOW_MS - 10_000),
            // synthesize_speech bucket: t=1..4, drop 1 & 2, keep 3 & 4.
            entry("synthesize_speech", "ss-1", createdAt = NOW_MS - 40_000),
            entry("synthesize_speech", "ss-2", createdAt = NOW_MS - 30_000),
            entry("synthesize_speech", "ss-3", createdAt = NOW_MS - 20_000),
            entry("synthesize_speech", "ss-4", createdAt = NOW_MS - 10_000),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                keepLatestPerTool = 2,
                preserveLiveAssets = false,
            ),
            rig.ctx,
        )

        assertEquals(8, out.data.totalEntries)
        assertEquals(4, out.data.prunedCount)
        assertEquals(4, out.data.keptCount)
        assertEquals(
            setOf("gi-1", "gi-2", "ss-1", "ss-2"),
            out.data.prunedGcLockfileRows.map { it.assetId }.toSet(),
        )
        out.data.prunedGcLockfileRows.forEach { assertEquals("count", it.reason) }

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("gi-3", "gi-4", "ss-3", "ss-4"),
            refreshed.lockfile.entries.map { it.assetId.value }.toSet(),
        )
    }

    @Test fun combinedAgeAndCountIntersectByAnd() = runTest {
        val rig = rig()
        // maxAgeDays = 10, keepLatestPerTool = 2.
        // For generate_image bucket:
        //   - fresh-1 (t=now): passes both → keep
        //   - fresh-2 (t=now - 1d): passes both → keep (top-2 recent, within age)
        //   - mid (t=now - 5d): fails count (3rd-most-recent), within age → drop by "count"
        //   - stale (t=now - 20d): fails both → drop by "age+count"
        //   - stale-but-recent-in-its-tool (t=now - 30d): in its own one-element tool
        //     it's the most-recent, passes count; fails age → drop by "age"
        val entries = listOf(
            entry("generate_image", "fresh-1", createdAt = NOW_MS),
            entry("generate_image", "fresh-2", createdAt = NOW_MS - 1L * MS_PER_DAY),
            entry("generate_image", "mid", createdAt = NOW_MS - 5L * MS_PER_DAY),
            entry("generate_image", "stale", createdAt = NOW_MS - 20L * MS_PER_DAY),
            entry("lonely_tool", "solo-old", createdAt = NOW_MS - 30L * MS_PER_DAY),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                maxAgeDays = 10,
                keepLatestPerTool = 2,
                preserveLiveAssets = false,
            ),
            rig.ctx,
        )

        assertEquals(5, out.data.totalEntries)
        assertEquals(3, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)

        val byAsset = out.data.prunedGcLockfileRows.associateBy { it.assetId }
        assertEquals("count", byAsset["mid"]?.reason)
        assertEquals("age+count", byAsset["stale"]?.reason)
        assertEquals("age", byAsset["solo-old"]?.reason)

        assertEquals(
            listOf("age", "count"),
            out.data.policiesApplied.filter { it != "liveAssetGuard" && it != "pinGuard" },
        )
    }

    @Test fun preserveLiveAssetsTrueRescuesWouldBeDroppedEntry() = runTest {
        val rig = rig()
        val entries = listOf(
            // Old by age, in one-entry tool bucket, but asset is live → rescued.
            entry("generate_image", "live-asset", createdAt = NOW_MS - 100L * MS_PER_DAY),
            // Old, asset not in project → pruned.
            entry("generate_image", "dead-asset", createdAt = NOW_MS - 100L * MS_PER_DAY),
        )
        seed(rig, assetIds = listOf("live-asset"), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                maxAgeDays = 30,
                preserveLiveAssets = true,
            ),
            rig.ctx,
        )

        assertEquals(2, out.data.totalEntries)
        assertEquals(1, out.data.prunedCount)
        assertEquals(1, out.data.keptCount)
        assertEquals(1, out.data.keptByLiveAssetGuardCount)
        assertEquals(
            listOf("dead-asset"),
            out.data.prunedGcLockfileRows.map { it.assetId },
        )
        assertTrue("liveAssetGuard" in out.data.policiesApplied)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("live-asset"),
            refreshed.lockfile.entries.map { it.assetId.value }.toSet(),
        )
    }

    @Test fun preserveLiveAssetsFalseIgnoresGuard() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "live-asset", createdAt = NOW_MS - 100L * MS_PER_DAY),
            entry("generate_image", "dead-asset", createdAt = NOW_MS - 100L * MS_PER_DAY),
        )
        seed(rig, assetIds = listOf("live-asset"), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                maxAgeDays = 30,
                preserveLiveAssets = false,
            ),
            rig.ctx,
        )

        assertEquals(2, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertEquals(0, out.data.keptByLiveAssetGuardCount)
        assertTrue("liveAssetGuard" !in out.data.policiesApplied)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.entries.isEmpty())
    }

    @Test fun dryRunDoesNotMutateStore() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "old-1", createdAt = NOW_MS - 100L * MS_PER_DAY),
            entry("generate_image", "old-2", createdAt = NOW_MS - 100L * MS_PER_DAY),
            entry("generate_image", "fresh", createdAt = NOW_MS),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                maxAgeDays = 30,
                preserveLiveAssets = false,
                dryRun = true,
            ),
            rig.ctx,
        )

        assertEquals(true, out.data.dryRun)
        assertEquals(2, out.data.prunedCount)

        // Store untouched — all three rows still present.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(3, refreshed.lockfile.entries.size)
        assertEquals(
            listOf("old-1", "old-2", "fresh"),
            refreshed.lockfile.entries.map { it.assetId.value },
        )
    }

    @Test fun bothPoliciesNullIsNoOpWithInformativeMessage() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1", createdAt = NOW_MS - 100L * MS_PER_DAY),
            entry("generate_image", "a-2", createdAt = NOW_MS),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p"),
            rig.ctx,
        )

        assertEquals(2, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(0, out.data.keptByLiveAssetGuardCount)
        assertTrue(out.data.prunedGcLockfileRows.isEmpty())
        assertTrue(out.data.policiesApplied.isEmpty())
        assertTrue(
            "prune-lockfile" in out.outputForLlm,
            "no-op message should point at prune-lockfile, was: ${out.outputForLlm}",
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(2, refreshed.lockfile.entries.size)
    }

    @Test fun maxAgeZeroDropsAnythingNotCreatedAtNow() = runTest {
        val rig = rig()
        val entries = listOf(
            // Exactly "now" — at-threshold is kept (strictly-older semantics).
            entry("generate_image", "now-ms", createdAt = NOW_MS),
            // One ms older than now — strictly older, drops.
            entry("generate_image", "one-ms-older", createdAt = NOW_MS - 1),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", maxAgeDays = 0, preserveLiveAssets = false),
            rig.ctx,
        )

        assertEquals(1, out.data.prunedCount)
        assertEquals(
            listOf("one-ms-older"),
            out.data.prunedGcLockfileRows.map { it.assetId },
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("now-ms"),
            refreshed.lockfile.entries.map { it.assetId.value }.toSet(),
        )
    }

    @Test fun emptyLockfileIsNoOp() = runTest {
        val rig = rig()
        seed(rig, assetIds = emptyList(), entries = emptyList())

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", maxAgeDays = 7),
            rig.ctx,
        )

        assertEquals(0, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertTrue(out.data.prunedGcLockfileRows.isEmpty())

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.entries.isEmpty())
    }

    @Test fun keepLatestPerToolZeroDropsEverythingInCountPolicy() = runTest {
        val rig = rig()
        val entries = listOf(
            entry("generate_image", "a-1", createdAt = NOW_MS),
            entry("generate_image", "a-2", createdAt = NOW_MS - 1000),
            entry("synthesize_speech", "b-1", createdAt = NOW_MS),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                keepLatestPerTool = 0,
                preserveLiveAssets = false,
            ),
            rig.ctx,
        )

        assertEquals(3, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertEquals(
            setOf("a-1", "a-2", "b-1"),
            out.data.prunedGcLockfileRows.map { it.assetId }.toSet(),
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertTrue(refreshed.lockfile.entries.isEmpty())
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
                ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "ghost", maxAgeDays = 7),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun negativeMaxAgeDaysRejected() = runTest {
        val rig = rig()
        seed(rig, assetIds = emptyList(), entries = emptyList())

        assertFailsWith<IllegalArgumentException> {
            ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
                ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", maxAgeDays = -1),
                rig.ctx,
            )
        }
    }

    @Test fun negativeKeepLatestPerToolRejected() = runTest {
        val rig = rig()
        seed(rig, assetIds = emptyList(), entries = emptyList())

        assertFailsWith<IllegalArgumentException> {
            ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
                ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", keepLatestPerTool = -1),
                rig.ctx,
            )
        }
    }

    @Test fun pinnedRowSurvivesStrictPolicySweep() = runTest {
        val rig = rig()
        val ancient = NOW_MS - 30L * MS_PER_DAY
        val entries = listOf(
            // An ancient entry that's policy-eligible to drop — but pinned.
            entry("generate_image", "pinned-hero", createdAt = ancient).copy(pinned = true),
            entry("generate_image", "ancient-other", createdAt = ancient),
            entry("generate_image", "fresh", createdAt = NOW_MS),
        )
        seed(rig, assetIds = emptyList(), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            // maxAgeDays=7 drops both ancient rows; preserveLiveAssets=false removes
            // the live-asset safety net so only the pin guard can rescue the hero.
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile",
                projectId = "p",
                maxAgeDays = 7,
                preserveLiveAssets = false,
            ),
            rig.ctx,
        )

        assertEquals(3, out.data.totalEntries)
        assertEquals(1, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(1, out.data.keptByPinCount)
        assertEquals(0, out.data.keptByLiveAssetGuardCount)
        assertEquals(setOf("ancient-other"), out.data.prunedGcLockfileRows.map { it.assetId }.toSet())
        assertTrue(out.data.policiesApplied.contains("pinGuard"))

        // The pinned row + the fresh row survived; only ancient-other dropped.
        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("pinned-hero", "fresh"),
            refreshed.lockfile.entries.map { it.assetId.value }.toSet(),
        )
        // Pin flag itself survived the mutation round-trip.
        assertTrue(refreshed.lockfile.findByAssetId(AssetId("pinned-hero"))!!.pinned)
    }

    @Test fun pinGuardAttributionTakesPriorityOverLiveAssetGuard() = runTest {
        val rig = rig()
        // Entry is pinned AND its asset is live. Both guards would rescue it
        // — we want the pin guard's accounting to win so the user can tell
        // whether it was protection-by-pin or protection-by-live-asset.
        val entries = listOf(
            entry(
                "generate_image",
                "hero",
                createdAt = NOW_MS - 30L * MS_PER_DAY,
            ).copy(pinned = true),
        )
        seed(rig, assetIds = listOf("hero"), entries = entries)

        val out = ProjectMaintenanceActionTool(rig.store, NoopMaintenanceEngine, fixedClock).execute(
            ProjectMaintenanceActionTool.Input(action = "gc-lockfile", projectId = "p", maxAgeDays = 7),
            rig.ctx,
        )

        assertEquals(0, out.data.prunedCount)
        assertEquals(1, out.data.keptByPinCount)
        assertEquals(0, out.data.keptByLiveAssetGuardCount)
    }
}
