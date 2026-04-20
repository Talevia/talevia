package io.talevia.core.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ApplyFilterTool
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end exercise of SessionRevert: seed a session with a user→assistant
 * turn that mutates the timeline via AddClipTool, fire a second mutating turn
 * (ApplyFilterTool), then revert to the anchor at the first user message and
 * verify (a) the second turn's messages are gone, (b) the timeline rolled back
 * to the pre-turn snapshot, (c) the bus emitted `SessionReverted`.
 */
class SessionRevertTest {

    @Test
    fun revertToUserAnchorDropsSubsequentTurnsAndRestoresTimeline() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = SqlDelightProjectStore(db)
        val media = InMemoryMediaStorage()
        val perms = AllowAllPermissionService()
        val revert = SessionRevert(sessions, projects, bus)

        val sessionId = SessionId("s-rev")
        val projectId = ProjectId("p-rev")
        val t0 = Clock.System.now()
        sessions.createSession(
            Session(
                id = sessionId,
                projectId = projectId,
                title = "test",
                createdAt = t0,
                updatedAt = t0,
            ),
        )
        projects.upsert("test", Project(projectId, Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))
        val asset = media.import(MediaSource.File("/tmp/a.mp4")) {
            io.talevia.core.domain.MediaMetadata(duration = 10.seconds)
        }

        // Turn 1: user message u1, assistant a1 running add_clip → mutates timeline.
        val u1 = MessageId("u1")
        val a1 = MessageId("a1")
        sessions.appendMessage(
            Message.User(u1, sessionId, t0.plus(1.milliseconds), "default", ModelRef("anthropic", "claude-opus-4-7")),
        )
        sessions.appendMessage(
            Message.Assistant(a1, sessionId, t0.plus(2.milliseconds), u1, ModelRef("anthropic", "claude-opus-4-7")),
        )
        fun ctxFor(call: String, msg: MessageId) = ToolContext(
            sessionId = sessionId,
            messageId = msg,
            callId = CallId(call),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { sessions.upsertPart(it) },
            messages = emptyList(),
        )
        val addClip = AddClipTool(projects, media)
        val addResp = addClip.execute(
            AddClipTool.Input(projectId = projectId.value, assetId = asset.id.value),
            ctxFor("c-add", a1),
        )
        val clipId = addResp.data.clipId
        assertEquals(1, projects.get(projectId)!!.timeline.tracks.flatMap { it.clips }.size)

        // Turn 2: user message u2, assistant a2 running apply_filter → second mutation.
        val u2 = MessageId("u2")
        val a2 = MessageId("a2")
        sessions.appendMessage(
            Message.User(u2, sessionId, t0.plus(3.milliseconds), "default", ModelRef("anthropic", "claude-opus-4-7")),
        )
        sessions.appendMessage(
            Message.Assistant(a2, sessionId, t0.plus(4.milliseconds), u2, ModelRef("anthropic", "claude-opus-4-7")),
        )
        ApplyFilterTool(projects).execute(
            ApplyFilterTool.Input(projectId = projectId.value, clipId = clipId, filterName = "blur"),
            ctxFor("c-filt", a2),
        )
        assertEquals(
            1,
            projects.get(projectId)!!.timeline.tracks
                .flatMap { it.clips }.filterIsInstance<io.talevia.core.domain.Clip.Video>()
                .single().filters.size,
        )

        // Drive the revert inside a Turbine collector so the SessionReverted event
        // is observed synchronously with the mutation. Anchor is the assistant
        // reply to turn 1 (a1) — keeps its timeline snapshot so the revert
        // exercises the timeline-restore branch.
        lateinit var result: SessionRevert.Result
        bus.events.filterIsInstance<BusEvent.SessionReverted>().test(timeout = 5.seconds) {
            val run = async { revert.revertToMessage(sessionId, a1, projectId) }
            val evt = awaitItem()
            result = run.await()
            assertEquals(a1, evt.anchorMessageId)
            assertEquals(sessionId, evt.sessionId)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, result.deletedMessages)
        assertNotNull(result.appliedSnapshotPartId)
        assertEquals(1, result.restoredClipCount)

        // Message state.
        val remaining = sessions.listMessages(sessionId).map { it.id }
        assertEquals(listOf(u1, a1), remaining)

        // Timeline state.
        val restoredClips = projects.get(projectId)!!.timeline.tracks
            .flatMap { it.clips }.filterIsInstance<io.talevia.core.domain.Clip.Video>()
        assertEquals(1, restoredClips.size, "clip from turn 1 should survive")
        assertEquals(0, restoredClips.single().filters.size, "filter from turn 2 should be gone")

        driver.close()
    }

    @Test
    fun revertWithNoPriorSnapshotLeavesTimelineUntouched() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = SqlDelightProjectStore(db)
        val revert = SessionRevert(sessions, projects, bus)

        val sessionId = SessionId("s")
        val projectId = ProjectId("p")
        val t0 = Clock.System.now()
        sessions.createSession(Session(sessionId, projectId, "t", createdAt = t0, updatedAt = t0))
        projects.upsert("t", Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))

        val u1 = MessageId("u1")
        val a1 = MessageId("a1")
        val u2 = MessageId("u2")
        sessions.appendMessage(Message.User(u1, sessionId, t0.plus(1.milliseconds), "a", ModelRef("anthropic", "m")))
        sessions.appendMessage(
            Message.Assistant(a1, sessionId, t0.plus(2.milliseconds), u1, ModelRef("anthropic", "m")),
        )
        sessions.appendMessage(Message.User(u2, sessionId, t0.plus(3.milliseconds), "a", ModelRef("anthropic", "m")))

        val result = revert.revertToMessage(sessionId, u1, projectId)
        assertEquals(2, result.deletedMessages)
        assertNull(result.appliedSnapshotPartId)

        driver.close()
    }

    @Test
    fun revertToLatestMessageIsNoOp() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = SqlDelightProjectStore(db)
        val revert = SessionRevert(sessions, projects, bus)

        val sessionId = SessionId("s")
        val projectId = ProjectId("p")
        val t0 = Clock.System.now()
        sessions.createSession(Session(sessionId, projectId, "t", createdAt = t0, updatedAt = t0))
        projects.upsert("t", Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))

        val u1 = MessageId("u1")
        sessions.appendMessage(Message.User(u1, sessionId, t0.plus(1.milliseconds), "a", ModelRef("anthropic", "m")))

        val result = revert.revertToMessage(sessionId, u1, projectId)
        assertEquals(0, result.deletedMessages)

        driver.close()
    }

    @Test
    fun revertWithMismatchedSessionThrows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = SqlDelightProjectStore(db)
        val revert = SessionRevert(sessions, projects, bus)

        val sessionA = SessionId("sA")
        val sessionB = SessionId("sB")
        val projectId = ProjectId("p")
        val t0 = Clock.System.now()
        sessions.createSession(Session(sessionA, projectId, "a", createdAt = t0, updatedAt = t0))
        sessions.createSession(Session(sessionB, projectId, "b", createdAt = t0, updatedAt = t0))
        projects.upsert("t", Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))

        val mA = MessageId("mA")
        sessions.appendMessage(Message.User(mA, sessionA, t0.plus(1.milliseconds), "a", ModelRef("anthropic", "m")))

        assertFailsWith<IllegalArgumentException> {
            revert.revertToMessage(sessionB, mA, projectId)
        }
        assertFailsWith<IllegalArgumentException> {
            revert.revertToMessage(sessionA, MessageId("unknown"), projectId)
        }

        driver.close()
    }

    @Test
    fun partIdFieldReferenced() {
        // keep PartId import linted-in even if future test edits drop it.
        assertTrue(PartId::class.simpleName == "PartId")
    }
}
