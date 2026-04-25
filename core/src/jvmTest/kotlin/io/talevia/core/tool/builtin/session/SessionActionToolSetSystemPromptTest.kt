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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `session_action(action="set_system_prompt", systemPromptOverride=…)` —
 * the per-session prompt-swap verb tied to [Session.systemPromptOverride].
 */
class SessionActionToolSetSystemPromptTest {

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW_MS)
    }

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(sid: String = "s-1"): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val ctx = ToolContext(
            sessionId = SessionId(sid),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private suspend fun seed(
        store: SqlDelightSessionStore,
        sid: String = "s-1",
        existingOverride: String? = null,
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_600_000_000_000L)
        val s = Session(
            id = SessionId(sid),
            projectId = ProjectId("p"),
            title = "t",
            createdAt = now,
            updatedAt = now,
            systemPromptOverride = existingOverride,
        )
        store.createSession(s)
        return s
    }

    @Test fun setsOverrideOnSessionRow() = runTest {
        val rig = rig()
        seed(rig.store)

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(
                action = "set_system_prompt",
                systemPromptOverride = "ROLE: code reviewer",
            ),
            rig.ctx,
        ).data

        assertEquals("set_system_prompt", out.action)
        assertNull(out.previousSystemPromptOverride)
        assertEquals("ROLE: code reviewer", out.newSystemPromptOverride)

        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertEquals("ROLE: code reviewer", refreshed.systemPromptOverride)
        assertEquals(NOW_MS, refreshed.updatedAt.toEpochMilliseconds())
    }

    @Test fun nullClearsExistingOverride() = runTest {
        // Setting then clearing must drop the override entirely so
        // subsequent turns see the Agent default again — null-as-clear
        // is the documented contract.
        val rig = rig()
        seed(rig.store, existingOverride = "ROLE: legacy")

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(
                action = "set_system_prompt",
                systemPromptOverride = null,
            ),
            rig.ctx,
        ).data

        assertEquals("ROLE: legacy", out.previousSystemPromptOverride)
        assertNull(out.newSystemPromptOverride)
        assertNull(rig.store.getSession(SessionId("s-1"))!!.systemPromptOverride)
    }

    @Test fun emptyStringIsLegitimateNonNullOverride() = runTest {
        // Empty string must NOT be conflated with null — it represents
        // a deliberate "run with no Agent default" setting and is
        // distinguishable on subsequent reads.
        val rig = rig()
        seed(rig.store)

        SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(
                action = "set_system_prompt",
                systemPromptOverride = "",
            ),
            rig.ctx,
        )

        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        // Distinct from null — must be the empty string verbatim.
        assertEquals("", refreshed.systemPromptOverride)
    }

    @Test fun idempotentReWriteIsNoOp() = runTest {
        // Writing the same value twice should not bump updatedAt the
        // second time — preserves "no-op" semantics so a tool's
        // bookkeeping doesn't pretend the session changed.
        val rig = rig()
        seed(rig.store, existingOverride = "ROLE: code reviewer")

        // First call to register the existing baseline updatedAt — we
        // seed a fixed timestamp via `seed()` so we can detect the
        // no-op by checking it's not bumped to NOW_MS.
        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(
                action = "set_system_prompt",
                systemPromptOverride = "ROLE: code reviewer",
            ),
            rig.ctx,
        ).data

        assertEquals("ROLE: code reviewer", out.previousSystemPromptOverride)
        assertEquals("ROLE: code reviewer", out.newSystemPromptOverride)
        // No-op didn't write — updatedAt must still be the seed time, not NOW_MS.
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertEquals(1_600_000_000_000L, refreshed.updatedAt.toEpochMilliseconds())
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(rig.store, fixedClock).execute(
                SessionActionTool.Input(
                    action = "set_system_prompt",
                    sessionId = "ghost",
                    systemPromptOverride = "x",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun toolStaysAtSessionWriteTier() = runTest {
        // Permission tier must stay at session.write — set_system_prompt
        // is a non-destructive mutation (the previous value is echoed
        // back in the output for undo). Only `delete` upgrades to
        // session.destructive.
        val tool = SessionActionTool(rig().store, fixedClock)
        val rawJson = """{"action":"set_system_prompt","systemPromptOverride":"x"}"""
        val tier = tool.permission.permissionFrom.invoke(rawJson)
        assertEquals("session.write", tier)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
