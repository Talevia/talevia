package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ForkProjectToolTest {

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

    private fun fakeAsset(id: AssetId): MediaAsset = MediaAsset(
        id = id,
        source = MediaSource.File("/tmp/${id.value}.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun videoClip(id: String, asset: AssetId): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 2.seconds),
        sourceRange = TimeRange(0.seconds, 2.seconds),
        assetId = asset,
    )

    @Test fun forksFromCurrentStateWhenSnapshotIdNull() = runTest {
        val rig = rig()
        val asset = AssetId("a-1")
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c-1", asset))))),
                assets = listOf(fakeAsset(asset)),
            ),
        )
        val tool = ForkProjectTool(rig.store)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "What if alt cut",
            ),
            rig.ctx,
        )

        assertEquals("p", out.data.sourceProjectId)
        assertEquals("proj-what-if-alt-cut", out.data.newProjectId)
        assertNull(out.data.branchedFromSnapshotId)
        assertEquals(1, out.data.clipCount)

        val fork = rig.store.get(ProjectId("proj-what-if-alt-cut"))!!
        assertEquals(ProjectId("proj-what-if-alt-cut"), fork.id)
        assertEquals(1, fork.timeline.tracks.flatMap { it.clips }.size)
        assertEquals(1, fork.assets.size)
        assertTrue(fork.snapshots.isEmpty(), "fork must start with a clean snapshots list")

        // Source project unchanged.
        val source = rig.store.get(ProjectId("p"))!!
        assertEquals(1, source.timeline.tracks.flatMap { it.clips }.size)
    }

    @Test fun forksFromExplicitSnapshotPayload() = runTest {
        val rig = rig()
        val originalAsset = AssetId("a-original")
        val snapshotAsset = AssetId("a-snapshot")
        // Source project: 2 clips currently, but snapshot v1 only has 1.
        val v1Captured = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip("c-snap", snapshotAsset)))),
            ),
            assets = listOf(fakeAsset(snapshotAsset)),
        )
        rig.store.upsert(
            "test",
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            TrackId("v1"),
                            listOf(videoClip("c-current-1", originalAsset), videoClip("c-current-2", originalAsset)),
                        ),
                    ),
                ),
                assets = listOf(fakeAsset(originalAsset)),
                snapshots = listOf(
                    ProjectSnapshot(ProjectSnapshotId("snap-v1"), "v1", 1_000L, v1Captured),
                ),
            ),
        )
        val tool = ForkProjectTool(rig.store)

        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "Branch off v1",
                snapshotId = "snap-v1",
            ),
            rig.ctx,
        )

        assertEquals("snap-v1", out.data.branchedFromSnapshotId)
        assertEquals(1, out.data.clipCount, "fork must reflect the snapshot, not current state")

        val fork = rig.store.get(ProjectId("proj-branch-off-v1"))!!
        val clip = fork.timeline.tracks.flatMap { it.clips }.single()
        assertEquals(snapshotAsset, (clip as Clip.Video).assetId)
        assertTrue(fork.snapshots.isEmpty())
    }

    @Test fun acceptsExplicitNewProjectId() = runTest {
        val rig = rig()
        rig.store.upsert(
            "test",
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        val tool = ForkProjectTool(rig.store)
        val out = tool.execute(
            ForkProjectTool.Input(
                sourceProjectId = "p",
                newTitle = "any title",
                newProjectId = "my-explicit-fork-id",
            ),
            rig.ctx,
        )
        assertEquals("my-explicit-fork-id", out.data.newProjectId)
        assertNotNull(rig.store.get(ProjectId("my-explicit-fork-id")))
    }

    @Test fun failsLoudOnMissingSourceProject() = runTest {
        val rig = rig()
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(sourceProjectId = "ghost", newTitle = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun failsLoudOnMissingSnapshot() = runTest {
        val rig = rig()
        rig.store.upsert("test", Project(id = ProjectId("p"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "p",
                    newTitle = "x",
                    snapshotId = "ghost-snap",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost-snap"), ex.message)
    }

    @Test fun failsLoudOnDuplicateNewProjectId() = runTest {
        val rig = rig()
        rig.store.upsert("source", Project(id = ProjectId("src"), timeline = Timeline()))
        rig.store.upsert("dest", Project(id = ProjectId("dest"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ForkProjectTool.Input(
                    sourceProjectId = "src",
                    newTitle = "x",
                    newProjectId = "dest",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("dest"), ex.message)
    }

    @Test fun rejectsBlankTitle() = runTest {
        val rig = rig()
        rig.store.upsert("source", Project(id = ProjectId("src"), timeline = Timeline()))
        val tool = ForkProjectTool(rig.store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ForkProjectTool.Input(sourceProjectId = "src", newTitle = "  "),
                rig.ctx,
            )
        }
    }
}
