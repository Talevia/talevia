package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for `session_action(action=import)` —
 * symmetric with `session_action(action="export", format="json")`.
 *
 * Test invariants:
 * - Round-trip: export → import yields a session that the store
 *   reports identically (id, title, project binding, message count,
 *   part count).
 * - formatVersion mismatch fails loud (silent tolerance corrupts
 *   the target store on schema drift).
 * - Missing target project fails loud (this tool intentionally
 *   doesn't create projects).
 * - Existing-session-id collision fails loud (no silent overwrite).
 * - Missing envelope / blank envelope / missing project store
 *   each fail loud with a self-describing message.
 */
class SessionActionImportTest {

    private data class Rig(
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val sessions = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val projects = ProjectStoreTestKit.create()
        val pid = ProjectId("p-target")
        projects.upsert("demo", Project(id = pid, timeline = Timeline()))
        val ctx = ToolContext(
            sessionId = SessionId("dispatcher-session"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(sessions, projects, ctx, pid)
    }

    private fun seedExportableSession(pid: ProjectId): Triple<Session, List<Message>, List<Part>> {
        val now = Instant.fromEpochMilliseconds(1_700_000_001_000L)
        val sid = SessionId("s-exported")
        val session = Session(
            id = sid,
            projectId = pid,
            title = "test session",
            createdAt = now,
            updatedAt = now,
        )
        val u = Message.User(
            id = MessageId("u-1"),
            sessionId = sid,
            createdAt = now,
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        val a = Message.Assistant(
            id = MessageId("a-1"),
            sessionId = sid,
            createdAt = now,
            parentId = u.id,
            model = ModelRef("anthropic", "claude-opus-4-7"),
            tokens = TokenUsage(input = 10, output = 20),
            finish = FinishReason.STOP,
        )
        val text = Part.Text(
            id = PartId("p-1"),
            messageId = a.id,
            sessionId = sid,
            createdAt = now,
            text = "hello world",
        )
        return Triple(session, listOf(u, a), listOf(text))
    }

    private fun envelopeFromTriple(
        session: Session,
        messages: List<Message>,
        parts: List<Part>,
        formatVersion: String = SESSION_EXPORT_FORMAT_VERSION,
    ): String {
        val env = SessionEnvelope(
            formatVersion = formatVersion,
            session = session,
            messages = messages,
            parts = parts,
        )
        return JsonConfig.default.encodeToString(SessionEnvelope.serializer(), env)
    }

    @Test fun roundTripExportImportLandsIdenticalSession() = runTest {
        val rig = rig()
        val (session, messages, parts) = seedExportableSession(rig.pid)
        val envelope = envelopeFromTriple(session, messages, parts)

        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val result = tool.execute(
            SessionActionTool.Input(action = "import", envelope = envelope),
            rig.ctx,
        ).data

        assertEquals("import", result.action)
        assertEquals(session.id.value, result.sessionId)
        assertEquals(session.title, result.title)
        assertEquals(SESSION_EXPORT_FORMAT_VERSION, result.importedFormatVersion)
        assertEquals(2, result.importedMessageCount)
        assertEquals(1, result.importedPartCount)

        val landed = assertNotNull(rig.sessions.getSession(session.id))
        assertEquals(session.title, landed.title)
        assertEquals(session.projectId, landed.projectId)
        assertEquals(2, rig.sessions.listMessages(session.id).size)
        assertEquals(1, rig.sessions.listSessionParts(session.id).size)
    }

    @Test fun mismatchedFormatVersionFailsLoud() = runTest {
        val rig = rig()
        val (session, messages, parts) = seedExportableSession(rig.pid)
        val envelope = envelopeFromTriple(session, messages, parts, formatVersion = "talevia-session-export-v999")
        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(action = "import", envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("formatVersion"), ex.message)
        assertTrue(ex.message!!.contains("talevia-session-export-v999"), ex.message)
    }

    @Test fun missingTargetProjectFailsLoud() = runTest {
        val rig = rig()
        val (session, messages, parts) = seedExportableSession(ProjectId("p-not-on-this-machine"))
        val envelope = envelopeFromTriple(session, messages, parts)
        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(action = "import", envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("p-not-on-this-machine"), ex.message)
        assertTrue(ex.message!!.contains("not registered"), ex.message)
    }

    @Test fun collidingSessionIdRefusesOverwrite() = runTest {
        val rig = rig()
        val (session, messages, parts) = seedExportableSession(rig.pid)
        // Pre-create the same session id in the target store.
        rig.sessions.createSession(session.copy(title = "existing"))

        val envelope = envelopeFromTriple(session, messages, parts)
        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(action = "import", envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains(session.id.value), ex.message)
        assertTrue(ex.message!!.contains("already exists"), ex.message)
    }

    @Test fun missingEnvelopeFailsLoud() = runTest {
        // `debt-split-session-action-tool-input-phase1b` (cycle 53):
        // missing-required-field validation moved from the handler's
        // `error("…")` (IllegalStateException) to the verb decoder's
        // `requireNotNull(…) { … }` (IllegalArgumentException). Same
        // failure surface for the agent (loud throw with "envelope" in
        // the message), different exception type — phase 1b's design
        // accepts this break since the decoder is the typed boundary.
        val rig = rig()
        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                SessionActionTool.Input(action = "import"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("envelope"), ex.message)
    }

    @Test fun missingProjectStoreFailsLoud() = runTest {
        // SessionActionTool constructed without a ProjectStore (legacy
        // test rigs hit this) must fail import with a self-describing
        // message rather than NPE.
        val rig = rig()
        val (session, messages, parts) = seedExportableSession(rig.pid)
        val envelope = envelopeFromTriple(session, messages, parts)
        val tool = SessionActionTool(rig.sessions) // no projects=
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(action = "import", envelope = envelope),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ProjectStore"), ex.message)
    }

    @Test fun malformedEnvelopeFailsLoud() = runTest {
        val rig = rig()
        val tool = SessionActionTool(rig.sessions, projects = rig.projects)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(action = "import", envelope = "not json {"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("decode"), ex.message)
    }
}
