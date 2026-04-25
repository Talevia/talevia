package io.talevia.core.tool.builtin.session

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `session_action(action=remove_permission_rule)` —
 * symmetrical to the interactive `[Always]` add path that
 * StdinPermissionPrompt uses.
 */
class SessionActionRemovePermissionRuleTest {

    /** In-memory persistence with a settable initial state. */
    private class FakePersistence(initial: List<PermissionRule>) : PermissionRulesPersistence {
        var current: List<PermissionRule> = initial
            private set

        override suspend fun load(): List<PermissionRule> = current
        override suspend fun save(rules: List<PermissionRule>) {
            current = rules
        }
    }

    private fun ctx(sid: SessionId): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun newRig(): Triple<SqlDelightSessionStore, SessionId, FakePersistence> {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        io.talevia.core.db.TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(io.talevia.core.db.TaleviaDb(driver), EventBus())
        val sid = SessionId("s1")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val persistence = FakePersistence(emptyList())
        return Triple(store, sid, persistence)
    }

    @Test fun removesMatchingRuleAndReportsCount() = runTest {
        val (store, sid, persistence) = newRig()
        // Two persisted rules; remove only fs.write /tmp.
        persistence.save(
            listOf(
                PermissionRule("fs.write", "/tmp/x", PermissionAction.ALLOW),
                PermissionRule("network.fetch", "https://example.com/*", PermissionAction.ALLOW),
            ),
        )
        val tool = SessionActionTool(store, permissionRulesPersistence = persistence)
        val out = tool.execute(
            SessionActionTool.Input(
                sessionId = sid.value,
                action = "remove_permission_rule",
                permission = "fs.write",
                pattern = "/tmp/x",
            ),
            ctx(sid),
        ).data
        assertEquals(1, out.removedRuleCount)
        assertEquals(1, out.remainingRuleCount)
        assertEquals(listOf("network.fetch"), persistence.current.map { it.permission })
    }

    @Test fun noMatchIsCleanZeroNotError() = runTest {
        val (store, sid, persistence) = newRig()
        persistence.save(listOf(PermissionRule("fs.write", "/tmp/x", PermissionAction.ALLOW)))
        val tool = SessionActionTool(store, permissionRulesPersistence = persistence)
        val out = tool.execute(
            SessionActionTool.Input(
                sessionId = sid.value,
                action = "remove_permission_rule",
                permission = "network.fetch",
                pattern = "https://example.com/*",
            ),
            ctx(sid),
        ).data
        assertEquals(0, out.removedRuleCount, "no match → 0 removed")
        assertEquals(1, out.remainingRuleCount, "ruleset unchanged")
        assertEquals("fs.write", persistence.current.single().permission)
    }

    @Test fun duplicatesAllRemovedInOneCall() = runTest {
        val (store, sid, persistence) = newRig()
        // Same (permission, pattern) twice — legitimate when user clicked
        // Always on the same prompt across two sessions.
        persistence.save(
            listOf(
                PermissionRule("fs.write", "/tmp/x", PermissionAction.ALLOW),
                PermissionRule("fs.write", "/tmp/x", PermissionAction.ALLOW),
                PermissionRule("network.fetch", "*", PermissionAction.ASK),
            ),
        )
        val tool = SessionActionTool(store, permissionRulesPersistence = persistence)
        val out = tool.execute(
            SessionActionTool.Input(
                sessionId = sid.value,
                action = "remove_permission_rule",
                permission = "fs.write",
                pattern = "/tmp/x",
            ),
            ctx(sid),
        ).data
        assertEquals(2, out.removedRuleCount, "both duplicates removed in one call")
        assertEquals(1, out.remainingRuleCount, "only network.fetch survives")
    }

    @Test fun missingPermissionFailsLoud() = runTest {
        val (store, sid, persistence) = newRig()
        val tool = SessionActionTool(store, permissionRulesPersistence = persistence)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(
                    sessionId = sid.value,
                    action = "remove_permission_rule",
                    pattern = "/tmp",
                ),
                ctx(sid),
            )
        }
        assertTrue { ex.message?.contains("permission") == true }
    }

    @Test fun missingPatternFailsLoud() = runTest {
        val (store, sid, persistence) = newRig()
        val tool = SessionActionTool(store, permissionRulesPersistence = persistence)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SessionActionTool.Input(
                    sessionId = sid.value,
                    action = "remove_permission_rule",
                    permission = "fs.write",
                ),
                ctx(sid),
            )
        }
        assertTrue { ex.message?.contains("pattern") == true }
    }

    @Test fun noopPersistenceReportsZeroNotError() = runTest {
        val (store, sid, _) = newRig()
        // Default constructor uses Noop — should silently no-op rather
        // than fail on Desktop / Server / Android / iOS test rigs.
        val tool = SessionActionTool(store)
        val out = tool.execute(
            SessionActionTool.Input(
                sessionId = sid.value,
                action = "remove_permission_rule",
                permission = "fs.write",
                pattern = "/tmp",
            ),
            ctx(sid),
        ).data
        assertEquals(0, out.removedRuleCount)
        assertEquals(0, out.remainingRuleCount)
    }
}
