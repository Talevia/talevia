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

/**
 * Cycle 144 folded `fork_session` into
 * `session_action(action="fork")`. This suite continues to pin the
 * fork semantics — whole-history default, custom title override,
 * anchor-truncation, missing-parent + wrong-session-anchor errors,
 * fresh-id guarantee — but routes through the unified action
 * dispatcher.
 */
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

    private fun forkInput(
        sessionId: String,
        anchorMessageId: String? = null,
        newTitle: String? = null,
    ) = SessionActionTool.Input(
        action = "fork",
        sessionId = sessionId,
        anchorMessageId = anchorMessageId,
        newTitle = newTitle,
    )

    @Test fun forksWholeHistoryByDefault() = runTest {
        val rig = rig()
        seedSession(rig.store)

        val out = SessionActionTool(rig.store).execute(
            forkInput(sessionId = "s-parent"),
            rig.ctx,
        ).data

        // Output.sessionId carries the parent (the action's input target).
        assertEquals("s-parent", out.sessionId)
        assertEquals(3, out.forkCopiedMessageCount)
        assertNull(out.forkAnchorMessageId)
        assertNotNull(out.forkedSessionId)
        assertNotEquals("s-parent", out.forkedSessionId)
        // Default branch title = "<parent title> (fork)".
        assertEquals("my-session (fork)", out.newTitle)

        // Branch exists in the store with parentId backlink.
        val branch = rig.store.getSession(SessionId(out.forkedSessionId!!))!!
        assertEquals(SessionId("s-parent"), branch.parentId)
        assertEquals("my-session (fork)", branch.title)
    }

    @Test fun customTitleOverridesDefault() = runTest {
        val rig = rig()
        seedSession(rig.store)
        val out = SessionActionTool(rig.store).execute(
            forkInput(sessionId = "s-parent", newTitle = "Mei arc"),
            rig.ctx,
        ).data
        assertEquals("Mei arc", out.newTitle)
        assertEquals("Mei arc", rig.store.getSession(SessionId(out.forkedSessionId!!))!!.title)
    }

    @Test fun anchorTruncatesBranchHistory() = runTest {
        val rig = rig()
        seedSession(rig.store, messages = listOf(1_000L, 2_000L, 3_000L, 4_000L))

        val out = SessionActionTool(rig.store).execute(
            forkInput(
                sessionId = "s-parent",
                anchorMessageId = "m-s-parent-1", // second message
            ),
            rig.ctx,
        ).data

        assertEquals(2, out.forkCopiedMessageCount)
        assertEquals("m-s-parent-1", out.forkAnchorMessageId)
    }

    @Test fun unknownSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(rig.store).execute(
                forkInput(sessionId = "ghost"),
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
            SessionActionTool(rig.store).execute(
                forkInput(
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
        val out = SessionActionTool(rig.store).execute(
            forkInput(sessionId = "s-parent"),
            rig.ctx,
        ).data
        // Fresh UUID — isn't a reuse of parent id.
        assertNotNull(out.forkedSessionId)
        assertTrue(out.forkedSessionId!!.isNotBlank())
        assertNotEquals("s-parent", out.forkedSessionId)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertTrue(value != null, "expected non-null")
        return value!!
    }
}
