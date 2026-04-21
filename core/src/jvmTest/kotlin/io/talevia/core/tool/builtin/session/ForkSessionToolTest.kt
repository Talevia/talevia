package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForkSessionToolTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
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

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        id: String = "s-parent",
        title: String = "my-session",
        messages: List<Long> = listOf(1_000L, 2_000L, 3_000L),
    ) {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        store.createSession(
            Session(
                id = SessionId(id),
                projectId = ProjectId("p"),
                title = title,
                createdAt = now,
                updatedAt = now,
            ),
        )
        messages.forEachIndexed { i, at ->
            store.appendMessage(
                Message.User(
                    id = MessageId("m-${id}-$i"),
                    sessionId = SessionId(id),
                    createdAt = Instant.fromEpochMilliseconds(at),
                    agent = "default",
                    model = ModelRef("anthropic", "claude"),
                ),
            )
        }
    }

    @Test fun forksWholeHistoryByDefault() = runTest {
        val rig = rig()
        seedSession(rig.store)

        val out = ForkSessionTool(rig.store).execute(
            ForkSessionTool.Input(sessionId = "s-parent"),
            rig.ctx,
        ).data

        assertEquals("s-parent", out.parentSessionId)
        assertEquals(3, out.copiedMessageCount)
        assertNull(out.anchorMessageId)
        assertNotEquals("s-parent", out.newSessionId)
        // Default title = "<parent title> (fork)"
        assertEquals("my-session (fork)", out.newTitle)

        // Branch exists in the store with parentId backlink.
        val branch = rig.store.getSession(SessionId(out.newSessionId))!!
        assertEquals(SessionId("s-parent"), branch.parentId)
        assertEquals("my-session (fork)", branch.title)
    }

    @Test fun customTitleOverridesDefault() = runTest {
        val rig = rig()
        seedSession(rig.store)
        val out = ForkSessionTool(rig.store).execute(
            ForkSessionTool.Input(sessionId = "s-parent", newTitle = "Mei arc"),
            rig.ctx,
        ).data
        assertEquals("Mei arc", out.newTitle)
        assertEquals("Mei arc", rig.store.getSession(SessionId(out.newSessionId))!!.title)
    }

    @Test fun anchorTruncatesBranchHistory() = runTest {
        val rig = rig()
        seedSession(rig.store, messages = listOf(1_000L, 2_000L, 3_000L, 4_000L))

        val out = ForkSessionTool(rig.store).execute(
            ForkSessionTool.Input(
                sessionId = "s-parent",
                anchorMessageId = "m-s-parent-1", // second message
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.copiedMessageCount)
        assertEquals("m-s-parent-1", out.anchorMessageId)
    }

    @Test fun unknownSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ForkSessionTool(rig.store).execute(
                ForkSessionTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("session_query(select=sessions)"), ex.message)
    }

    @Test fun anchorFromWrongSessionFailsLoud() = runTest {
        val rig = rig()
        seedSession(rig.store, id = "s-a")
        seedSession(rig.store, id = "s-b")

        // Anchor belongs to s-b, not s-a.
        val ex = assertFailsWith<IllegalArgumentException> {
            ForkSessionTool(rig.store).execute(
                ForkSessionTool.Input(
                    sessionId = "s-a",
                    anchorMessageId = "m-s-b-0",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("does not belong"), ex.message)
    }

    @Test fun forkedSessionHasFreshId() = runTest {
        val rig = rig()
        seedSession(rig.store)
        val out = ForkSessionTool(rig.store).execute(
            ForkSessionTool.Input(sessionId = "s-parent"),
            rig.ctx,
        ).data
        // Fresh UUID — isn't a reuse of parent id.
        assertTrue(out.newSessionId.isNotBlank())
        assertNotEquals("s-parent", out.newSessionId)
    }
}
