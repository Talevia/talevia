package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.PermissionRuleRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=permission_rules)` — surface the
 * (builtin + session-scoped) ruleset so the agent doesn't have to
 * reconstruct it from the system prompt.
 */
class SessionQueryPermissionRulesTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun seedSession(
        sessionRules: List<PermissionRule> = emptyList(),
    ): Pair<SqlDelightSessionStore, SessionId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val sid = SessionId("rules-test")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
                permissionRules = sessionRules,
            ),
        )
        return store to sid
    }

    @Test fun returnsBuiltinRulesByDefault() = runTest {
        val (store, sid) = seedSession()
        val out = SessionQueryTool(sessions = store).execute(
            SessionQueryTool.Input(select = "permission_rules", sessionId = sid.value),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(PermissionRuleRow.serializer())
        assertEquals(DefaultPermissionRuleset.rules.size, rows.size)
        assertTrue(rows.all { it.source == "builtin" })
        assertTrue("expected echo rule with allow action") {
            rows.any { it.permission == "echo" && it.action == "ALLOW" }
        }
    }

    @Test fun sessionScopedRulesAppearAfterBuiltinsTagged() = runTest {
        val sessionRule = PermissionRule(
            permission = "fs.read",
            pattern = "/Users/*",
            action = PermissionAction.ALLOW,
        )
        val (store, sid) = seedSession(sessionRules = listOf(sessionRule))
        val out = SessionQueryTool(sessions = store).execute(
            SessionQueryTool.Input(select = "permission_rules", sessionId = sid.value),
            ctx(),
        ).data
        val rows = out.rows.decodeRowsAs(PermissionRuleRow.serializer())
        // Total = builtin count + 1 session rule.
        assertEquals(DefaultPermissionRuleset.rules.size + 1, rows.size)
        // The session rule appears at the tail (after all builtins).
        val tail = rows.last()
        assertEquals("fs.read", tail.permission)
        assertEquals("/Users/*", tail.pattern)
        assertEquals("ALLOW", tail.action)
        assertEquals("session", tail.source)
    }

    @Test fun shadowingRuleSurfacesBothBuiltinAndOverride() = runTest {
        // Builtin: aigc.generate is ASK by default. Session override: ALLOW
        // for a specific pattern. Both must appear so the agent sees the
        // override exists AND the underlying default.
        val override = PermissionRule(
            permission = "aigc.generate",
            pattern = "*",
            action = PermissionAction.ALLOW,
        )
        val (store, sid) = seedSession(sessionRules = listOf(override))
        val rows = SessionQueryTool(sessions = store).execute(
            SessionQueryTool.Input(select = "permission_rules", sessionId = sid.value),
            ctx(),
        ).data.rows.decodeRowsAs(PermissionRuleRow.serializer())
        val aigcRows = rows.filter { it.permission == "aigc.generate" }
        assertEquals(2, aigcRows.size, "both builtin + override must surface")
        assertTrue(aigcRows.any { it.source == "builtin" && it.action == "ASK" })
        assertTrue(aigcRows.any { it.source == "session" && it.action == "ALLOW" })
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val ex = kotlin.runCatching {
            SessionQueryTool(sessions = store).execute(
                SessionQueryTool.Input(select = "permission_rules", sessionId = "ghost"),
                ctx(),
            )
        }.exceptionOrNull()
        assertTrue("expected error: $ex") {
            ex?.message?.contains("Session ghost not found") == true
        }
    }
}
