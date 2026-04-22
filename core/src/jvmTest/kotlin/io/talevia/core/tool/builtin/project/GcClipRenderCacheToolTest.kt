package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GcClipRenderCacheToolTest {

    private companion object {
        const val NOW_MS: Long = 1_700_000_000_000L
        const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW_MS)
    }

    /**
     * Records every [deleteMezzanine] path so tests can assert which on-disk
     * mezzanines were actually requested to be removed. Returns `true` for
     * every path in [present]; everything else comes back `false` (simulating
     * a file that was already gone when GC tried to delete it).
     */
    private class FakeEngine(
        val present: MutableSet<String>,
    ) : VideoEngine {
        val deleteCalls = mutableListOf<String>()
        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = kotlin.time.Duration.ZERO)
        override fun render(timeline: Timeline, output: OutputSpec): Flow<io.talevia.core.platform.RenderProgress> =
            emptyFlow()
        override suspend fun thumbnail(
            asset: io.talevia.core.AssetId,
            source: MediaSource,
            time: kotlin.time.Duration,
        ): ByteArray = ByteArray(0)
        override suspend fun deleteMezzanine(path: String): Boolean {
            deleteCalls += path
            return present.remove(path)
        }
    }

    private data class Rig(
        val store: SqlDelightProjectStore,
        val engine: FakeEngine,
        val ctx: ToolContext,
    )

    private fun rig(presentPaths: Set<String> = emptySet()): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val engine = FakeEngine(present = presentPaths.toMutableSet())
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, engine, ctx)
    }

    private fun entry(
        fp: String,
        createdAt: Long = NOW_MS,
    ): ClipRenderCacheEntry = ClipRenderCacheEntry(
        fingerprint = fp,
        mezzaninePath = "/tmp/.talevia-render-cache/p/$fp.mp4",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        durationSeconds = 2.0,
        createdAtEpochMs = createdAt,
    )

    private suspend fun seed(
        rig: Rig,
        entries: List<ClipRenderCacheEntry>,
    ) {
        var cache = ClipRenderCache.EMPTY
        for (e in entries) cache = cache.append(e)
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(),
                clipRenderCache = cache,
            ),
        )
    }

    @Test fun noPolicyIsNoopPointsAtPolicyArg() = runTest {
        val rig = rig()
        seed(rig, listOf(entry("a"), entry("b")))

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(2, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertTrue(out.data.policiesApplied.isEmpty())
        assertTrue(rig.engine.deleteCalls.isEmpty())
        assertTrue("policy" in out.outputForLlm)
        // Cache unchanged.
        assertEquals(2, rig.store.get(ProjectId("p"))!!.clipRenderCache.entries.size)
    }

    @Test fun ageOnlyPolicyDropsOlderThanCutoffKeepsBoundary() = runTest {
        val rig = rig(
            presentPaths = setOf(
                "/tmp/.talevia-render-cache/p/just-past.mp4",
                "/tmp/.talevia-render-cache/p/ancient.mp4",
            ),
        )
        // maxAgeDays = 7 → cutoff = NOW - 7d. Strictly-older drops; equal keeps.
        seed(
            rig,
            listOf(
                entry("fresh", createdAt = NOW_MS),
                entry("boundary", createdAt = NOW_MS - 7L * MS_PER_DAY),
                entry("just-past", createdAt = NOW_MS - 7L * MS_PER_DAY - 1),
                entry("ancient", createdAt = NOW_MS - 30L * MS_PER_DAY),
            ),
        )

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", maxAgeDays = 7),
            rig.ctx,
        )

        assertEquals(4, out.data.totalEntries)
        assertEquals(2, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(setOf("just-past", "ancient"), out.data.prunedEntries.map { it.fingerprint }.toSet())
        out.data.prunedEntries.forEach { assertEquals("age", it.reason) }
        assertTrue(out.data.prunedEntries.all { it.fileDeleted })

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("fresh", "boundary"),
            refreshed.clipRenderCache.entries.map { it.fingerprint }.toSet(),
        )
    }

    @Test fun countOnlyPolicyKeepsMostRecentN() = runTest {
        val rig = rig(
            presentPaths = setOf(
                "/tmp/.talevia-render-cache/p/a.mp4",
                "/tmp/.talevia-render-cache/p/b.mp4",
                "/tmp/.talevia-render-cache/p/c.mp4",
                "/tmp/.talevia-render-cache/p/d.mp4",
                "/tmp/.talevia-render-cache/p/e.mp4",
            ),
        )
        seed(
            rig,
            listOf(
                entry("a", createdAt = NOW_MS - 50_000),
                entry("b", createdAt = NOW_MS - 40_000),
                entry("c", createdAt = NOW_MS - 30_000),
                entry("d", createdAt = NOW_MS - 20_000),
                entry("e", createdAt = NOW_MS - 10_000),
            ),
        )

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", keepLastN = 2),
            rig.ctx,
        )

        assertEquals(5, out.data.totalEntries)
        assertEquals(3, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(setOf("a", "b", "c"), out.data.prunedEntries.map { it.fingerprint }.toSet())
        out.data.prunedEntries.forEach { assertEquals("count", it.reason) }

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(
            setOf("d", "e"),
            refreshed.clipRenderCache.entries.map { it.fingerprint }.toSet(),
        )
    }

    @Test fun andSemanticsRowMustPassBothPolicies() = runTest {
        val rig = rig(
            presentPaths = setOf(
                "/tmp/.talevia-render-cache/p/old-kept.mp4",
                "/tmp/.talevia-render-cache/p/old-dropped.mp4",
                "/tmp/.talevia-render-cache/p/new-dropped.mp4",
                "/tmp/.talevia-render-cache/p/new-kept.mp4",
            ),
        )
        // keepLastN=2 selects the oldest 2 for drop by count.
        // maxAgeDays=7 selects "old-*" (both older than cutoff) for drop by age.
        // AND → each row drops iff it fails BOTH policies? No — matches GcLockfileTool
        // semantics: row must PASS both to survive; drops if it fails ANY enabled
        // policy. "age+count" reason applies to entries failing both.
        seed(
            rig,
            listOf(
                entry("old-dropped", createdAt = NOW_MS - 30L * MS_PER_DAY),
                entry("old-kept", createdAt = NOW_MS - 20L * MS_PER_DAY),
                entry("new-dropped", createdAt = NOW_MS - 1_000),
                entry("new-kept", createdAt = NOW_MS - 500),
            ),
        )

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", maxAgeDays = 7, keepLastN = 2),
            rig.ctx,
        )

        // count policy (keepLastN=2, sorted by createdAtEpochMs desc):
        //   keep = [new-kept, new-dropped]; drop = [old-kept, old-dropped]
        // age policy (maxAgeDays=7):
        //   drop = [old-dropped, old-kept] (both strictly older than NOW - 7d)
        // Reason mapping:
        //   old-dropped → age+count (in both sets)
        //   old-kept    → age+count (in both sets)
        //   new-*       → neither policy selects them → survive
        // Net: drop 2, keep 2.
        assertEquals(2, out.data.prunedCount)
        assertEquals(2, out.data.keptCount)
        assertEquals(
            setOf("old-dropped", "old-kept"),
            out.data.prunedEntries.map { it.fingerprint }.toSet(),
        )
        out.data.prunedEntries.forEach { assertEquals("age+count", it.reason) }
        assertEquals(setOf("age", "count"), out.data.policiesApplied.toSet())
    }

    @Test fun dryRunReportsButDoesNotMutateOrDeleteFiles() = runTest {
        val rig = rig(
            presentPaths = setOf(
                "/tmp/.talevia-render-cache/p/a.mp4",
                "/tmp/.talevia-render-cache/p/b.mp4",
            ),
        )
        seed(rig, listOf(entry("a", createdAt = NOW_MS - 10L * MS_PER_DAY), entry("b")))

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", maxAgeDays = 7, dryRun = true),
            rig.ctx,
        )

        assertEquals(1, out.data.prunedCount)
        assertTrue(out.data.dryRun)
        out.data.prunedEntries.forEach { assertFalse(it.fileDeleted) }
        // Engine never asked to delete under dry-run.
        assertTrue(rig.engine.deleteCalls.isEmpty())
        // Cache rows intact.
        assertEquals(2, rig.store.get(ProjectId("p"))!!.clipRenderCache.entries.size)
    }

    @Test fun emptyCacheIsNoopEvenWithPolicy() = runTest {
        val rig = rig()
        seed(rig, emptyList())

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", keepLastN = 3),
            rig.ctx,
        )

        assertEquals(0, out.data.totalEntries)
        assertEquals(0, out.data.prunedCount)
        assertTrue(out.data.prunedEntries.isEmpty())
        assertTrue(rig.engine.deleteCalls.isEmpty())
    }

    @Test fun missingProjectFailsLoud() = runTest {
        val rig = rig()

        val ex = assertFailsWith<IllegalStateException> {
            GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
                GcClipRenderCacheTool.Input(projectId = "nonexistent", keepLastN = 1),
                rig.ctx,
            )
        }
        assertTrue("nonexistent" in ex.message!!)
    }

    @Test fun alreadyMissingOnDiskStillPrunesRowMarksFileDeletedFalse() = runTest {
        val rig = rig(presentPaths = emptySet()) // nothing on disk
        seed(rig, listOf(entry("a", createdAt = NOW_MS - 30L * MS_PER_DAY)))

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", maxAgeDays = 7),
            rig.ctx,
        )

        assertEquals(1, out.data.prunedCount)
        // Row pruned even though file was already gone.
        assertEquals(0, rig.store.get(ProjectId("p"))!!.clipRenderCache.entries.size)
        // fileDeleted=false because Files.deleteIfExists returned false.
        assertFalse(out.data.prunedEntries.single().fileDeleted)
        // Engine was still asked.
        assertEquals(1, rig.engine.deleteCalls.size)
    }

    @Test fun negativeMaxAgeDaysRejected() = runTest {
        val rig = rig()
        seed(rig, listOf(entry("a")))

        val ex = assertFailsWith<IllegalArgumentException> {
            GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
                GcClipRenderCacheTool.Input(projectId = "p", maxAgeDays = -1),
                rig.ctx,
            )
        }
        assertTrue("maxAgeDays" in ex.message!!)
    }

    @Test fun negativeKeepLastNRejected() = runTest {
        val rig = rig()
        seed(rig, listOf(entry("a")))

        val ex = assertFailsWith<IllegalArgumentException> {
            GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
                GcClipRenderCacheTool.Input(projectId = "p", keepLastN = -1),
                rig.ctx,
            )
        }
        assertTrue("keepLastN" in ex.message!!)
    }

    @Test fun keepLastNZeroDropsEverything() = runTest {
        val rig = rig(
            presentPaths = setOf(
                "/tmp/.talevia-render-cache/p/a.mp4",
                "/tmp/.talevia-render-cache/p/b.mp4",
            ),
        )
        seed(rig, listOf(entry("a"), entry("b")))

        val out = GcClipRenderCacheTool(rig.store, rig.engine, fixedClock).execute(
            GcClipRenderCacheTool.Input(projectId = "p", keepLastN = 0),
            rig.ctx,
        )

        assertEquals(2, out.data.prunedCount)
        assertEquals(0, out.data.keptCount)
        assertEquals(0, rig.store.get(ProjectId("p"))!!.clipRenderCache.entries.size)
    }
}
