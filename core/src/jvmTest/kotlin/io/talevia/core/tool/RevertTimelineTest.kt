package io.talevia.core.tool

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.session.Part
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
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
        val projects = SqlDelightProjectStore(db)
        val media = InMemoryMediaStorage()
        val perms = AllowAllPermissionService()

        val sessionId = SessionId("s")
        val projectId = ProjectId("p")
        projects.upsert("test", Project(projectId, Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))
        fun ctxFor(call: String, msg: String) = ToolContext(
            sessionId = sessionId,
            messageId = MessageId(msg),
            callId = CallId(call),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { sessions.upsertPart(it) },
            messages = emptyList(),
        )
        val ctx = ctxFor("c-add", "m-mutate")

        // Seed a fake asset so AddClipTool has something to reference without ffmpeg.
        val asset = media.import(MediaSource.File("/tmp/fake.mp4")) {
            io.talevia.core.domain.MediaMetadata(duration = 10.seconds)
        }

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

        val addClip = AddClipTool(projects, media)
        val addResult = addClip.execute(
            AddClipTool.Input(projectId = projectId.value, assetId = asset.id.value),
            ctx,
        )
        assertTrue(addResult.outputForLlm.contains("Timeline snapshot:"), "add_clip should surface snapshot id")
        assertEquals(1, projects.get(projectId)!!.timeline.tracks.flatMap { it.clips }.size)

        val revert = RevertTimelineTool(sessions, projects)
        val revertResult = revert.execute(
            RevertTimelineTool.Input(projectId = projectId.value, snapshotPartId = baselineSnapshotId.value),
            ctxFor("c-revert", "m-revert"),
        )
        assertEquals(0, revertResult.data.clipCount)
        assertEquals(0, projects.get(projectId)!!.timeline.tracks.flatMap { it.clips }.size)

        val snapshots = sessions.listSessionParts(sessionId).filterIsInstance<Part.TimelineSnapshot>()
        // baseline + add_clip's snapshot + revert's snapshot
        assertEquals(3, snapshots.size)

        driver.close()
    }

    @Test
    fun revertToMidStateDropsLaterMutations() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = SqlDelightProjectStore(db)
        val media = InMemoryMediaStorage()
        val perms = AllowAllPermissionService()

        val sessionId = SessionId("s2")
        val projectId = ProjectId("p2")
        projects.upsert("test", Project(projectId, Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))
        val asset = media.import(MediaSource.File("/tmp/a.mp4")) {
            io.talevia.core.domain.MediaMetadata(duration = 10.seconds)
        }

        fun ctxFor(call: String, msg: String) = ToolContext(
            sessionId = sessionId,
            messageId = MessageId(msg),
            callId = CallId(call),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { sessions.upsertPart(it) },
            messages = emptyList(),
        )

        val addClip = AddClipTool(projects, media)
        val addResp = addClip.execute(
            AddClipTool.Input(projectId = projectId.value, assetId = asset.id.value),
            ctxFor("c1", "m1"),
        )
        val clipId = addResp.data.clipId
        // Extract the snapshot PartId surfaced in outputForLlm (mid-state: one clip, no filter).
        val midSnapshotId = Regex("Timeline snapshot: (\\S+)").find(addResp.outputForLlm)!!.groupValues[1]

        val applyFilter = ApplyFilterTool(projects)
        applyFilter.execute(
            ApplyFilterTool.Input(projectId = projectId.value, clipId = clipId, filterName = "blur"),
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
