package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevertSessionToolTest {

    private data class Rig(
        val tool: RevertSessionTool,
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(RevertSessionTool(sessions, projects, bus), sessions, projects, ctx)
    }

    private suspend fun seed(rig: Rig) {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        rig.projects.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        rig.sessions.createSession(
            Session(
                id = SessionId("s-1"),
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun appendUser(
        rig: Rig,
        id: String,
        atMs: Long,
    ) {
        rig.sessions.appendMessage(
            Message.User(
                id = MessageId(id),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(atMs),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    @Test fun revertDropsSubsequentMessages() = runTest {
        val rig = rig()
        seed(rig)
        appendUser(rig, "u-1", atMs = 1_000L)
        appendUser(rig, "u-2", atMs = 2_000L)
        appendUser(rig, "u-3", atMs = 3_000L)

        val out = rig.tool.execute(
            RevertSessionTool.Input(
                sessionId = "s-1",
                anchorMessageId = "u-1",
                projectId = "p",
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.deletedMessages)
        assertEquals("u-1", out.anchorMessageId)
        // No timeline snapshot existed → appliedSnapshotPartId null, counts zero.
        assertNull(out.appliedSnapshotPartId)
        assertEquals(0, out.restoredClipCount)

        val remaining = rig.sessions.listMessages(SessionId("s-1")).map { it.id.value }
        assertEquals(listOf("u-1"), remaining)
    }

    @Test fun anchorThatIsLatestIsNoOp() = runTest {
        val rig = rig()
        seed(rig)
        appendUser(rig, "u-1", atMs = 1_000L)

        val out = rig.tool.execute(
            RevertSessionTool.Input(
                sessionId = "s-1",
                anchorMessageId = "u-1",
                projectId = "p",
            ),
            rig.ctx,
        ).data

        assertEquals(0, out.deletedMessages)
    }

    @Test fun unknownAnchorFailsLoud() = runTest {
        val rig = rig()
        seed(rig)

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                RevertSessionTool.Input(
                    sessionId = "s-1",
                    anchorMessageId = "ghost",
                    projectId = "p",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun anchorFromWrongSessionFailsLoud() = runTest {
        val rig = rig()
        seed(rig)
        // Put u-other on a different session.
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        rig.sessions.createSession(
            Session(
                id = SessionId("s-other"),
                projectId = ProjectId("p"),
                title = "other",
                createdAt = now,
                updatedAt = now,
            ),
        )
        rig.sessions.appendMessage(
            Message.User(
                id = MessageId("u-other"),
                sessionId = SessionId("s-other"),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                RevertSessionTool.Input(
                    sessionId = "s-1",
                    anchorMessageId = "u-other",
                    projectId = "p",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("belongs to session"), ex.message)
    }
}
