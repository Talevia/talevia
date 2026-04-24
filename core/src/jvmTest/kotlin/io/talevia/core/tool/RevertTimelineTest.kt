package io.talevia.core.tool

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.session.Part
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.video.ClipActionTool
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.RevertTimelineTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end exercise of the revert/undo flow: seed a project, call a mutating
 * tool, grab the snapshot PartId it surfaces in `outputForLlm`, then revert and
 * verify the timeline drops the clip it added.
 */
class RevertTimelineTest {

    @Test
    fun addClipEmitsSnapshotAndRevertRestoresEmptyTimeline() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val sessionId = SessionId("s")
        val projectId = ProjectId("p")
        // Seed a fake asset so ClipActionTool(action=add) has something to reference without ffmpeg.
        val asset = MediaAsset(
            id = AssetId("fake-source"),
            source = MediaSource.File("/tmp/fake.mp4"),
            metadata = MediaMetadata(duration = 10.seconds),
        )
        projects.upsert(
            "test",
            Project(projectId, assets = listOf(asset), timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )
        fun ctxFor(call: String, msg: String) = ToolContext(
            sessionId = sessionId,
            messageId = MessageId(msg),
            callId = CallId(call),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { sessions.upsertPart(it) },
            messages = emptyList(),
        )
        val ctx = ctxFor("c-add", "m-mutate")

        // Capture the project baseline (empty) as the snapshot to revert to.
        val baselineSnapshotId = PartId("baseline")
        sessions.upsertPart(
            Part.TimelineSnapshot(
                id = baselineSnapshotId,
                messageId = MessageId("m-seed"),
                sessionId = sessionId,
                createdAt = Clock.System.now(),
                timeline = Timeline(),
                producedByCallId = null,
            ),
        )

        val addClip = ClipActionTool(projects)
        val addResult = addClip.execute(
            ClipActionTool.Input(
                projectId = projectId.value,
                action = "add",
                addItems = listOf(ClipActionTool.AddItem(assetId = asset.id.value)),
            ),
            ctx,
        )
        assertTrue(addResult.outputForLlm.contains("Snapshot:"), "clip_action(action=add) should surface snapshot id")
        assertEquals(1, projects.get(projectId)!!.timeline.tracks.flatMap { it.clips }.size)

        val revert = RevertTimelineTool(sessions, projects)
        val revertResult = revert.execute(
            RevertTimelineTool.Input(projectId = projectId.value, snapshotPartId = baselineSnapshotId.value),
            ctxFor("c-revert", "m-revert"),
        )
        assertEquals(0, revertResult.data.clipCount)
        assertEquals(0, projects.get(projectId)!!.timeline.tracks.flatMap { it.clips }.size)

        val snapshots = sessions.listSessionParts(sessionId).filterIsInstance<Part.TimelineSnapshot>()
        // baseline + clip_action(action=add)'s snapshot + revert's snapshot
        assertEquals(3, snapshots.size)

        driver.close()
    }

    @Test
    fun revertToMidStateDropsLaterMutations() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val sessionId = SessionId("s2")
        val projectId = ProjectId("p2")
        val asset = MediaAsset(
            id = AssetId("fake-a"),
            source = MediaSource.File("/tmp/a.mp4"),
            metadata = MediaMetadata(duration = 10.seconds),
        )
        projects.upsert(
            "test",
            Project(projectId, assets = listOf(asset), timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        fun ctxFor(call: String, msg: String) = ToolContext(
            sessionId = sessionId,
            messageId = MessageId(msg),
            callId = CallId(call),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { sessions.upsertPart(it) },
            messages = emptyList(),
        )

        val addClip = ClipActionTool(projects)
        val addResp = addClip.execute(
            ClipActionTool.Input(
                projectId = projectId.value,
                action = "add",
                addItems = listOf(ClipActionTool.AddItem(assetId = asset.id.value)),
            ),
            ctxFor("c1", "m1"),
        )
        val clipId = addResp.data.added.single().clipId
        val midSnapshotId = addResp.data.snapshotId

        val applyFilter = FilterActionTool(projects)
        applyFilter.execute(
            FilterActionTool.Input(
                projectId = projectId.value,
                action = "apply",
                clipIds = listOf(clipId),
                filterName = "blur",
            ),
            ctxFor("c2", "m2"),
        )
        // After the filter: one clip with one filter.
        val withFilter = projects.get(projectId)!!.timeline.tracks
            .flatMap { it.clips }.filterIsInstance<io.talevia.core.domain.Clip.Video>()
        assertEquals(1, withFilter.single().filters.size)

        // Revert to mid-state: clip should remain, filter should be gone.
        val revert = RevertTimelineTool(sessions, projects)
        revert.execute(
            RevertTimelineTool.Input(projectId = projectId.value, snapshotPartId = midSnapshotId),
            ctxFor("c3", "m3"),
        )
        val reverted = projects.get(projectId)!!.timeline.tracks
            .flatMap { it.clips }.filterIsInstance<io.talevia.core.domain.Clip.Video>()
        assertEquals(1, reverted.size, "clip added before filter should survive revert")
        assertEquals(0, reverted.single().filters.size, "filter applied after the target snapshot should be gone")

        driver.close()
    }
}
