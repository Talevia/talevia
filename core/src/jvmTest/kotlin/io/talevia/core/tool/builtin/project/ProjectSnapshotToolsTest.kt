package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.SnapshotRow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProjectSnapshotToolsTest {

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

    private class FixedClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private fun videoClip(id: String, asset: AssetId): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 2.seconds),
        sourceRange = TimeRange(0.seconds, 2.seconds),
        assetId = asset,
    )

    private fun seedProject(id: String, clips: List<Clip.Video> = emptyList()): Project = Project(
        id = ProjectId(id),
        timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), clips))),
        assets = clips.map { fakeAsset(it.assetId) },
    )

    private fun fakeAsset(id: AssetId): MediaAsset = MediaAsset(
        id = id,
        source = MediaSource.File("/tmp/${id.value}.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun fakeProvenance(): GenerationProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake-model",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    /**
     * Convenience wrapper that runs `project_query(select=snapshots)` and returns
     * (total, decoded-rows) so the existing `list_project_snapshots`-style
     * assertions in this file can be preserved after the consolidation cycle.
     */
    private suspend fun listSnapshots(
        rig: Rig,
        projectId: String,
        clock: Clock = Clock.System,
        maxAgeDays: Int? = null,
        limit: Int? = null,
    ): Pair<Int, List<SnapshotRow>> {
        val out = ProjectQueryTool(rig.store, clock).execute(
            ProjectQueryTool.Input(
                projectId = projectId,
                select = "snapshots",
                maxAgeDays = maxAgeDays,
                limit = limit,
            ),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SnapshotRow.serializer()),
            out.rows,
        )
        return out.total to rows
    }

    @Test fun saveDefaultsLabelToTimestampAndStashesPayload() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        rig.store.upsert("test", seedProject("p", listOf(videoClip("c-1", AssetId("a-1")))))
        val tool = SaveProjectSnapshotTool(rig.store, clock)

        val out = tool.execute(
            SaveProjectSnapshotTool.Input(projectId = "p"),
            rig.ctx,
        )

        assertEquals(1_700_000_000_000L, out.data.capturedAtEpochMs)
        assertTrue(out.data.label.contains("1700000000000"), "default label embeds the timestamp: ${out.data.label}")
        assertEquals(1, out.data.totalSnapshotCount)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(1, refreshed.snapshots.size)
        val snap = refreshed.snapshots.single()
        assertEquals(out.data.snapshotId, snap.id.value)
        assertEquals(1, snap.project.timeline.tracks.flatMap { it.clips }.size)
        // Nested snapshots cleared so we don't grow quadratically.
        assertTrue(snap.project.snapshots.isEmpty(), "captured payload's snapshots list must be cleared")
    }

    @Test fun saveAcceptsExplicitLabelAndAccumulatesSnapshots() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        rig.store.upsert("test", seedProject("p"))
        val tool = SaveProjectSnapshotTool(rig.store, clock)

        tool.execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "v1"), rig.ctx)
        clock.instant = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        tool.execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "v2"), rig.ctx)

        val refreshed = rig.store.get(ProjectId("p"))!!
        assertEquals(listOf("v1", "v2"), refreshed.snapshots.map { it.label })
    }

    @Test fun listReturnsMostRecentFirstWithCounts() = runTest {
        val rig = rig()
        val baseProject = seedProject("p", listOf(videoClip("c-1", AssetId("a-1"))))
        val captured1 = baseProject.copy(snapshots = emptyList())
        val captured2 = captured1.copy(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        listOf(videoClip("c-1", AssetId("a-1")), videoClip("c-2", AssetId("a-2"))),
                    ),
                ),
            ),
        )
        rig.store.upsert(
            "test",
            baseProject.copy(
                snapshots = listOf(
                    ProjectSnapshot(ProjectSnapshotId("snap-1"), "earliest", 1_000L, captured1),
                    ProjectSnapshot(ProjectSnapshotId("snap-2"), "newest", 9_000L, captured2),
                ),
            ),
        )

        val (total, rows) = listSnapshots(rig, "p")

        assertEquals(2, total)
        assertEquals(listOf("snap-2", "snap-1"), rows.map { it.snapshotId })
        assertEquals(2, rows[0].clipCount)
        assertEquals(1, rows[1].clipCount)
    }

    @Test fun listOnEmptyProjectReturnsEmpty() = runTest {
        val rig = rig()
        rig.store.upsert("test", seedProject("p"))
        val (total, rows) = listSnapshots(rig, "p")
        assertEquals(0, total)
        assertTrue(rows.isEmpty())
    }

    @Test fun listMaxAgeDaysDropsOlderSnapshots() = runTest {
        val rig = rig()
        val msPerDay = 24L * 60L * 60L * 1000L
        // Clock "now" is day 10.
        val now = 10L * msPerDay
        val clock = FixedClock(Instant.fromEpochMilliseconds(now))
        val base = seedProject("p")
        val captured = base.copy(snapshots = emptyList())
        rig.store.upsert(
            "test",
            base.copy(
                snapshots = listOf(
                    // Ancient (8 days before now).
                    ProjectSnapshot(ProjectSnapshotId("old"), "ancient", now - 8L * msPerDay, captured),
                    // 3 days before now — inside a 5-day window.
                    ProjectSnapshot(ProjectSnapshotId("mid"), "recent", now - 3L * msPerDay, captured),
                    // Right now.
                    ProjectSnapshot(ProjectSnapshotId("new"), "newest", now, captured),
                ),
            ),
        )

        val (total, rows) = listSnapshots(rig, "p", clock = clock, maxAgeDays = 5)

        // "old" dropped (strictly older than now-5d); "mid" + "new" kept, newest-first.
        assertEquals(2, total)
        assertEquals(listOf("new", "mid"), rows.map { it.snapshotId })
    }

    @Test fun listMaxAgeDaysZeroKeepsOnlyNowOrFuture() = runTest {
        val rig = rig()
        val now = 1_700_000_000_000L
        val clock = FixedClock(Instant.fromEpochMilliseconds(now))
        val base = seedProject("p")
        val captured = base.copy(snapshots = emptyList())
        rig.store.upsert(
            "test",
            base.copy(
                snapshots = listOf(
                    ProjectSnapshot(ProjectSnapshotId("a"), "a", now - 1L, captured),
                    ProjectSnapshot(ProjectSnapshotId("b"), "b", now, captured),
                ),
            ),
        )

        val (_, rows) = listSnapshots(rig, "p", clock = clock, maxAgeDays = 0)

        // Age 0 means the cutoff is "now"; anything strictly older drops.
        assertEquals(listOf("b"), rows.map { it.snapshotId })
    }

    @Test fun listLimitCapsOutputAfterNewestFirstSort() = runTest {
        val rig = rig()
        val base = seedProject("p")
        val captured = base.copy(snapshots = emptyList())
        // 5 snapshots with ascending timestamps so id -> order is obvious.
        val snaps = (1..5).map { i ->
            ProjectSnapshot(
                ProjectSnapshotId("s-$i"),
                "label-$i",
                i.toLong() * 1_000L,
                captured,
            )
        }
        rig.store.upsert("test", base.copy(snapshots = snaps))

        val (_, rows) = listSnapshots(rig, "p", limit = 2)

        // Newest-first then take 2 -> s-5, s-4.
        assertEquals(2, rows.size)
        assertEquals(listOf("s-5", "s-4"), rows.map { it.snapshotId })
    }

    @Test fun listDefaultSortIsNewestFirst() = runTest {
        val rig = rig()
        val base = seedProject("p")
        val captured = base.copy(snapshots = emptyList())
        // Store in ascending-captured order; the tool must still return descending.
        rig.store.upsert(
            "test",
            base.copy(
                snapshots = listOf(
                    ProjectSnapshot(ProjectSnapshotId("a"), "a", 1_000L, captured),
                    ProjectSnapshot(ProjectSnapshotId("b"), "b", 2_000L, captured),
                    ProjectSnapshot(ProjectSnapshotId("c"), "c", 3_000L, captured),
                ),
            ),
        )

        val (_, rows) = listSnapshots(rig, "p")

        assertEquals(listOf("c", "b", "a"), rows.map { it.snapshotId })
    }

    @Test fun listNegativeMaxAgeDaysRejected() = runTest {
        val rig = rig()
        rig.store.upsert("test", seedProject("p"))
        val ex = assertFailsWith<IllegalArgumentException> {
            listSnapshots(rig, "p", maxAgeDays = -1)
        }
        assertTrue(ex.message!!.contains("maxAgeDays"), ex.message)
    }

    @Test fun listLimitOutsideRangeIsClampedSilently() = runTest {
        // ProjectQueryTool's shared limit clamp replaces the old fail-loud
        // guard (the old tool threw on 0 / 501 — the unified primitive clamps
        // to 1..500 so tests + callers get stable behaviour). Verify each end
        // stays within the clamp rather than reopening fragile exception
        // wording.
        val rig = rig()
        val base = seedProject("p")
        val captured = base.copy(snapshots = emptyList())
        rig.store.upsert("test", base.copy(snapshots = listOf(
            ProjectSnapshot(ProjectSnapshotId("s"), "s", 1L, captured),
        )))
        val (_, rowsZero) = listSnapshots(rig, "p", limit = 0)
        val (_, rowsFive) = listSnapshots(rig, "p", limit = 501)
        assertEquals(1, rowsZero.size) // clamp to min 1 (only 1 snapshot exists)
        assertEquals(1, rowsFive.size) // clamp to max 500 (only 1 snapshot exists)
    }

    @Test fun restoreReplacesPayloadButPreservesSnapshotsListAndProjectId() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

        // Seed a project with one clip + one source node + one lockfile entry.
        val initialAsset = AssetId("a-original")
        val initialClip = videoClip("c-1", initialAsset)
        val initialProject = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(initialClip)))),
            assets = listOf(fakeAsset(initialAsset)),
            source = Source.EMPTY.addNode(
                SourceNode(
                    id = SourceNodeId("mei"),
                    kind = "core.consistency.character_ref",
                    body = buildJsonObject { put("name", JsonPrimitive("Mei")) },
                ),
            ),
            lockfile = Lockfile.EMPTY.append(
                LockfileEntry(
                    inputHash = "h-original",
                    toolId = "generate_image",
                    assetId = initialAsset,
                    provenance = fakeProvenance(),
                    sourceBinding = setOf(SourceNodeId("mei")),
                ),
            ),
        )
        rig.store.upsert("test", initialProject)

        // Capture snapshot v1.
        val save = SaveProjectSnapshotTool(rig.store, clock)
        val v1 = save.execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "v1"), rig.ctx)

        // Mutate the live project (drop the lockfile + add a second clip).
        val mutatedAsset = AssetId("a-new")
        rig.store.mutate(ProjectId("p")) { project ->
            project.copy(
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(initialClip, videoClip("c-2", mutatedAsset)),
                        ),
                    ),
                ),
                lockfile = Lockfile.EMPTY,
            )
        }
        // Capture snapshot v2 (post-mutation state) so we can prove restore preserves
        // the entire snapshots list, not just the one being restored.
        clock.instant = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        save.execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "v2"), rig.ctx)

        // Restore back to v1.
        val restore = RestoreProjectSnapshotTool(rig.store)
        val out = restore.execute(
            RestoreProjectSnapshotTool.Input(projectId = "p", snapshotId = v1.data.snapshotId),
            rig.ctx,
        )

        assertEquals(v1.data.snapshotId, out.data.snapshotId)
        assertEquals("v1", out.data.label)
        assertEquals(1, out.data.clipCount)

        val refreshed = rig.store.get(ProjectId("p"))!!
        // Project id preserved.
        assertEquals(ProjectId("p"), refreshed.id)
        // Restorable fields rolled back.
        assertEquals(1, refreshed.timeline.tracks.flatMap { it.clips }.size)
        assertEquals(1, refreshed.lockfile.entries.size)
        assertNotNull(refreshed.source.byId[SourceNodeId("mei")])
        // Snapshots list itself preserved (history is not a one-way trapdoor).
        assertEquals(
            listOf("v1", "v2"),
            refreshed.snapshots.map { it.label },
            "restore must preserve the snapshots list itself so history isn't lost",
        )
    }

    @Test fun restoreFailsLoudOnUnknownSnapshotId() = runTest {
        val rig = rig()
        rig.store.upsert("test", seedProject("p"))
        val ex = assertFailsWith<IllegalStateException> {
            RestoreProjectSnapshotTool(rig.store).execute(
                RestoreProjectSnapshotTool.Input(projectId = "p", snapshotId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun saveListRestoreRoundTrip() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        val project = seedProject("p", listOf(videoClip("c-1", AssetId("a-1"))))
        rig.store.upsert("test", project)

        SaveProjectSnapshotTool(rig.store, clock)
            .execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "v1"), rig.ctx)

        val (total, rows) = listSnapshots(rig, "p")
        assertEquals(1, total)
        val snapshotId = rows.single().snapshotId

        // Mutate then restore back.
        rig.store.mutate(ProjectId("p")) { it.copy(timeline = Timeline()) }
        assertEquals(0, rig.store.get(ProjectId("p"))!!.timeline.tracks.size)

        RestoreProjectSnapshotTool(rig.store).execute(
            RestoreProjectSnapshotTool.Input(projectId = "p", snapshotId = snapshotId),
            rig.ctx,
        )
        assertEquals(1, rig.store.get(ProjectId("p"))!!.timeline.tracks.size)
    }

    @Test fun deleteDropsTargetedSnapshotAndLeavesOthers() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        rig.store.upsert("test", seedProject("p"))

        val save = SaveProjectSnapshotTool(rig.store, clock)
        save.execute(SaveProjectSnapshotTool.Input(projectId = "p", label = "keep-me"), rig.ctx)
        clock.instant = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        val victim = save.execute(
            SaveProjectSnapshotTool.Input(projectId = "p", label = "drop-me"),
            rig.ctx,
        ).data.snapshotId

        val out = DeleteProjectSnapshotTool(rig.store).execute(
            DeleteProjectSnapshotTool.Input(projectId = "p", snapshotId = victim),
            rig.ctx,
        )

        assertEquals(victim, out.data.snapshotId)
        assertEquals("drop-me", out.data.label)
        assertEquals(1, out.data.remainingSnapshotCount)
        val remaining = rig.store.get(ProjectId("p"))!!.snapshots
        assertEquals(1, remaining.size)
        assertEquals("keep-me", remaining.single().label)
    }

    @Test fun deleteUnknownSnapshotThrows() = runTest {
        val rig = rig()
        rig.store.upsert("test", seedProject("p"))
        val tool = DeleteProjectSnapshotTool(rig.store)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                DeleteProjectSnapshotTool.Input(projectId = "p", snapshotId = "missing"),
                rig.ctx,
            )
        }
    }

    @Test fun deleteIsIdempotentFromUserPerspectiveAfterFirstRun() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        rig.store.upsert("test", seedProject("p"))

        val sid = SaveProjectSnapshotTool(rig.store, clock).execute(
            SaveProjectSnapshotTool.Input(projectId = "p", label = "v1"),
            rig.ctx,
        ).data.snapshotId

        val tool = DeleteProjectSnapshotTool(rig.store)
        tool.execute(DeleteProjectSnapshotTool.Input(projectId = "p", snapshotId = sid), rig.ctx)
        // second call throws — silent success would hide typos / stale ids.
        assertFailsWith<IllegalStateException> {
            tool.execute(DeleteProjectSnapshotTool.Input(projectId = "p", snapshotId = sid), rig.ctx)
        }
    }

    @Test fun deleteDoesNotAffectLiveProjectContents() = runTest {
        val rig = rig()
        val clock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        val clip = videoClip("c-1", AssetId("a-1"))
        rig.store.upsert("test", seedProject("p", listOf(clip)))

        val sid = SaveProjectSnapshotTool(rig.store, clock).execute(
            SaveProjectSnapshotTool.Input(projectId = "p", label = "v1"),
            rig.ctx,
        ).data.snapshotId

        DeleteProjectSnapshotTool(rig.store).execute(
            DeleteProjectSnapshotTool.Input(projectId = "p", snapshotId = sid),
            rig.ctx,
        )

        val refreshed = rig.store.get(ProjectId("p"))!!
        // Snapshots gone, but live timeline + assets intact.
        assertTrue(refreshed.snapshots.isEmpty())
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
        assertEquals(1, refreshed.assets.size)
    }
}
