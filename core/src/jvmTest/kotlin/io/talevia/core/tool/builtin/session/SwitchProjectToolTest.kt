package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitchProjectToolTest {

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW_MS)
    }

    private data class Rig(
        val sessions: SqlDelightSessionStore,
        val projects: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, EventBus())
        val projects = SqlDelightProjectStore(db)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(sessions, projects, ctx)
    }

    private suspend fun seedSession(
        sessions: SqlDelightSessionStore,
        id: String = "s-1",
        originProjectId: String = "p-origin",
        currentProjectId: ProjectId? = null,
    ): Session {
        val now = Instant.fromEpochMilliseconds(PAST_MS)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId(originProjectId),
            title = "Untitled",
            createdAt = now,
            updatedAt = now,
            currentProjectId = currentProjectId,
        )
        sessions.createSession(s)
        return s
    }

    private suspend fun seedProject(projects: SqlDelightProjectStore, id: String) {
        projects.upsert(id, Project(id = ProjectId(id), timeline = Timeline()))
    }

    @Test fun defaultCurrentProjectIsNull() = runTest {
        val rig = rig()
        seedSession(rig.sessions)
        val s = rig.sessions.getSession(SessionId("s-1"))!!
        assertNull(s.currentProjectId)
    }

    @Test fun switchFromNullSetsBindingAndBumpsUpdatedAt() = runTest {
        val rig = rig()
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")

        val out = SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data

        assertEquals(null, out.previousProjectId)
        assertEquals("p-target", out.currentProjectId)
        assertTrue(out.changed)

        val refreshed = rig.sessions.getSession(SessionId("s-1"))!!
        assertEquals(ProjectId("p-target"), refreshed.currentProjectId)
        assertEquals(NOW_MS, refreshed.updatedAt.toEpochMilliseconds())
    }

    @Test fun changeBetweenProjectsWorks() = runTest {
        val rig = rig()
        seedSession(rig.sessions, currentProjectId = ProjectId("p-a"))
        seedProject(rig.projects, "p-a")
        seedProject(rig.projects, "p-b")

        val out = SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-b"),
            rig.ctx,
        ).data

        assertEquals("p-a", out.previousProjectId)
        assertEquals("p-b", out.currentProjectId)
        assertTrue(out.changed)
        val refreshed = rig.sessions.getSession(SessionId("s-1"))!!
        assertEquals(ProjectId("p-b"), refreshed.currentProjectId)
    }

    @Test fun sameIdIsNoOpAndDoesNotBumpUpdatedAt() = runTest {
        val rig = rig()
        seedSession(rig.sessions, currentProjectId = ProjectId("p-a"))
        seedProject(rig.projects, "p-a")

        val before = rig.sessions.getSession(SessionId("s-1"))!!.updatedAt

        val out = SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-a"),
            rig.ctx,
        ).data

        assertEquals("p-a", out.previousProjectId)
        assertEquals("p-a", out.currentProjectId)
        assertEquals(false, out.changed)
        val refreshed = rig.sessions.getSession(SessionId("s-1"))!!
        // updatedAt unchanged on no-op.
        assertEquals(before, refreshed.updatedAt)
    }

    @Test fun unknownProjectFailsLoudAndDoesNotMutate() = runTest {
        val rig = rig()
        seedSession(rig.sessions)
        // Deliberately DO NOT seed a project — target is a ghost id.

        val ex = assertFailsWith<IllegalStateException> {
            SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
                SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("p-ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_projects"), ex.message)
        // Session binding untouched.
        val refreshed = rig.sessions.getSession(SessionId("s-1"))!!
        assertNull(refreshed.currentProjectId)
    }

    @Test fun unknownSessionFailsLoud() = runTest {
        val rig = rig()
        seedProject(rig.projects, "p-real")

        val ex = assertFailsWith<IllegalStateException> {
            SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
                SwitchProjectTool.Input(sessionId = "ghost", projectId = "p-real"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_sessions"), ex.message)
    }

    @Test fun blankInputsAreRejected() = runTest {
        val rig = rig()
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-real")

        assertFailsWith<IllegalArgumentException> {
            SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
                SwitchProjectTool.Input(sessionId = "   ", projectId = "p-real"),
                rig.ctx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
                SwitchProjectTool.Input(sessionId = "s-1", projectId = ""),
                rig.ctx,
            )
        }
    }

    @Test fun legacySessionJsonWithoutCurrentProjectDecodesCleanly() = runTest {
        // Serialization compat (§3a #7): an old session JSON blob that predates
        // currentProjectId must decode with currentProjectId = null.
        val rig = rig()
        val driverLegacy = rig.sessions
        val legacyJson = """{
            "id": "s-legacy",
            "projectId": "p-legacy",
            "title": "Legacy",
            "createdAt": "2024-01-01T00:00:00Z",
            "updatedAt": "2024-01-01T00:00:00Z"
        }""".trimIndent()
        val decoded = io.talevia.core.JsonConfig.default.decodeFromString(Session.serializer(), legacyJson)
        assertEquals(SessionId("s-legacy"), decoded.id)
        assertEquals(ProjectId("p-legacy"), decoded.projectId)
        assertNull(decoded.currentProjectId)
        // And round-trips through the store without losing the field default.
        driverLegacy.createSession(decoded)
        val roundTripped = driverLegacy.getSession(SessionId("s-legacy"))!!
        assertNull(roundTripped.currentProjectId)
    }

    @Test fun toolContextReflectsBindingAfterRead() = runTest {
        // After a switch, Agent.run reads currentProjectId from the session
        // and threads it into ToolContext. We simulate that path here by
        // building a ToolContext from the newly read session.
        val rig = rig()
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-bound")

        SwitchProjectTool(rig.sessions, rig.projects, fixedClock).execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-bound"),
            rig.ctx,
        )

        val session = rig.sessions.getSession(SessionId("s-1"))!!
        assertNotNull(session.currentProjectId)
        val downstreamCtx = ToolContext(
            sessionId = session.id,
            messageId = MessageId("m-2"),
            callId = CallId("c-2"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
            currentProjectId = session.currentProjectId,
        )
        assertEquals(ProjectId("p-bound"), downstreamCtx.currentProjectId)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val PAST_MS = 1_600_000_000_000L
    }
}
