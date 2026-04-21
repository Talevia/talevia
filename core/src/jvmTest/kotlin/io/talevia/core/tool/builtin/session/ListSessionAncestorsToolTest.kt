package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
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

class ListSessionAncestorsToolTest {

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

    private suspend fun newSession(
        store: SqlDelightSessionStore,
        id: String,
        parentId: String? = null,
        archived: Boolean = false,
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = id,
            parentId = parentId?.let { SessionId(it) },
            createdAt = now,
            updatedAt = now,
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    @Test fun rootSessionHasEmptyChain() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")

        val out = ListSessionAncestorsTool(rig.store).execute(
            ListSessionAncestorsTool.Input(sessionId = "s-root"),
            rig.ctx,
        ).data

        assertEquals(0, out.depth)
        assertTrue(out.ancestors.isEmpty())
    }

    @Test fun deepChainWalkedParentToRoot() = runTest {
        val rig = rig()
        // s-root ← s-mid ← s-leaf
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-mid", parentId = "s-root")
        newSession(rig.store, "s-leaf", parentId = "s-mid")

        val out = ListSessionAncestorsTool(rig.store).execute(
            ListSessionAncestorsTool.Input(sessionId = "s-leaf"),
            rig.ctx,
        ).data

        assertEquals(2, out.depth)
        // Parent first, root last.
        assertEquals(listOf("s-mid", "s-root"), out.ancestors.map { it.id })
        // Root's parentId is null.
        assertNull(out.ancestors.last().parentId)
    }

    @Test fun archivedAncestorIncludedInChain() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-archived-mid", parentId = "s-root", archived = true)
        newSession(rig.store, "s-leaf", parentId = "s-archived-mid")

        val out = ListSessionAncestorsTool(rig.store).execute(
            ListSessionAncestorsTool.Input(sessionId = "s-leaf"),
            rig.ctx,
        ).data

        assertEquals(2, out.depth)
        val mid = out.ancestors.first { it.id == "s-archived-mid" }
        assertTrue(mid.archived)
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListSessionAncestorsTool(rig.store).execute(
                ListSessionAncestorsTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun brokenParentChainStopsCleanly() = runTest {
        val rig = rig()
        // s-child references a parent that doesn't exist — the walk stops at
        // that point rather than throwing.
        newSession(rig.store, "s-child", parentId = "s-missing-parent")

        val out = ListSessionAncestorsTool(rig.store).execute(
            ListSessionAncestorsTool.Input(sessionId = "s-child"),
            rig.ctx,
        ).data

        // The broken edge can't be resolved, so the chain is empty (no
        // ancestor successfully fetched).
        assertEquals(0, out.depth)
    }

    @Test fun directParentIsFirstInList() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-child", parentId = "s-root")

        val out = ListSessionAncestorsTool(rig.store).execute(
            ListSessionAncestorsTool.Input(sessionId = "s-child"),
            rig.ctx,
        ).data
        assertEquals(1, out.depth)
        assertEquals("s-root", out.ancestors.single().id)
    }
}
