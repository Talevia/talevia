package io.talevia.core.tool.builtin.session.action

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [executeRemovePermissionRule] —
 * `core/tool/builtin/session/action/SessionPermissionHandlers.kt`.
 * The `session_action(action="remove_permission_rule")`
 * handler that drops persisted Always rules. Cycle 192
 * audit: 77 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Required `permission` AND `pattern` (both non-
 *    blank); missing either throws with documented hint.**
 *    The hint references `session_query(select=
 *    permission_rules)` so the agent can self-correct.
 *
 * 2. **Match semantics: exact-match on BOTH permission +
 *    pattern; duplicates removed in one call.** Drift to
 *    "match permission only" or "match first only" would
 *    silently leave duplicates in place. Pinned via
 *    seeding the persistence with 2 identical rules and
 *    asserting both removed.
 *
 * 3. **No-match is a successful no-op with
 *    `removedRuleCount=0` AND `save` NOT called.** Drift
 *    to "always save" would mark the rules file dirty
 *    even on no-match (visible in atime / git diff if
 *    no normalisation is in play). Pinned by tracking
 *    save-call count on a fake persistence.
 */
class SessionPermissionHandlersTest {

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
        title: String = "session-$sid",
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )
        return sessionId
    }

    /** Captures save-call state so tests can assert on it. */
    private class CapturingPersistence(initial: List<PermissionRule>) :
        PermissionRulesPersistence {
        private var rules = initial
        var saveCallCount = 0
            private set

        override suspend fun load(): List<PermissionRule> = rules
        override suspend fun save(rules: List<PermissionRule>) {
            this.rules = rules
            saveCallCount += 1
        }

        fun current(): List<PermissionRule> = rules
    }

    private fun rule(permission: String, pattern: String): PermissionRule = PermissionRule(
        permission = permission,
        pattern = pattern,
        action = PermissionAction.ALLOW,
    )

    private fun context(sid: SessionId): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun input(
        sessionId: String? = null,
        permission: String? = null,
        pattern: String? = null,
    ): SessionActionTool.Input = SessionActionTool.Input(
        action = "remove_permission_rule",
        sessionId = sessionId,
        permission = permission,
        pattern = pattern,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingPermissionThrowsWithHint() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val persistence = CapturingPersistence(emptyList())

        val ex = assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = null, pattern = "/tmp/*"),
                ctx = context(sid),
            )
        }
        assertTrue(
            "requires `permission`" in (ex.message ?: ""),
            "requires phrase; got: ${ex.message}",
        )
        assertTrue(
            "session_query(select=permission_rules)" in (ex.message ?: ""),
            "discoverability hint; got: ${ex.message}",
        )
    }

    @Test fun missingPatternThrowsWithHint() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val persistence = CapturingPersistence(emptyList())

        val ex = assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = "fs.write", pattern = null),
                ctx = context(sid),
            )
        }
        assertTrue(
            "requires `pattern`" in (ex.message ?: ""),
            "requires phrase; got: ${ex.message}",
        )
        assertTrue(
            "session_query(select=permission_rules)" in (ex.message ?: ""),
            "discoverability hint; got: ${ex.message}",
        )
    }

    @Test fun blankPermissionAlsoRejected() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val persistence = CapturingPersistence(emptyList())

        // Empty AND whitespace both fail (per
        // `?.takeIf { it.isNotBlank() }`).
        assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = "   ", pattern = "x"),
                ctx = context(sid),
            )
        }
        assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = "", pattern = "x"),
                ctx = context(sid),
            )
        }
    }

    @Test fun blankPatternAlsoRejected() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val persistence = CapturingPersistence(emptyList())

        assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = "fs.write", pattern = "   "),
                ctx = context(sid),
            )
        }
    }

    @Test fun missingSessionThrowsWithDiscoverabilityHint() = runTest {
        val store = newStore()
        val persistence = CapturingPersistence(emptyList())

        val ex = assertFailsWith<IllegalStateException> {
            executeRemovePermissionRule(
                sessions = store,
                permissionRulesPersistence = persistence,
                input = input(permission = "fs.write", pattern = "x"),
                ctx = context(SessionId("ghost")),
            )
        }
        assertTrue("not found" in (ex.message ?: ""))
        assertTrue("session_query(select=sessions)" in (ex.message ?: ""))
    }

    // ── No-match no-op pin ───────────────────────────────────

    @Test fun noMatchingRuleIsSuccessfulNoOpWithSaveNotCalled() = runTest {
        // Marquee no-op pin: when no rule matches, the
        // handler returns successfully but DOES NOT call
        // save(). Drift to "always save" would mark the
        // rules file dirty even on no-match (file mtime
        // bumps, git diff shows no-op rewrite, cross-process
        // notifications fire unnecessarily).
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(rule("fs.read", "/tmp/*"), rule("web.fetch", "github.com"))
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "ghost", pattern = "ghost"),
            ctx = context(sid),
        )

        assertEquals(0, persistence.saveCallCount, "save NOT called on no-match")
        assertEquals(0, result.data.removedRuleCount)
        assertEquals(2, result.data.remainingRuleCount, "all initial rules preserved")
        assertTrue(
            "no matching rule" in result.outputForLlm,
            "verb cites no-match; got: ${result.outputForLlm}",
        )
    }

    // ── Single-match removal ─────────────────────────────────

    @Test fun singleMatchingRuleRemovedAndSaveCalled() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(
            rule("fs.read", "/tmp/*"),
            rule("fs.write", "/home/foo"),
            rule("web.fetch", "github.com"),
        )
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/home/foo"),
            ctx = context(sid),
        )

        assertEquals(1, persistence.saveCallCount, "save called exactly once")
        assertEquals(1, result.data.removedRuleCount)
        assertEquals(2, result.data.remainingRuleCount)
        // Persistence reflects the removal — fs.write/home/foo gone.
        assertContentEquals(
            listOf(rule("fs.read", "/tmp/*"), rule("web.fetch", "github.com")),
            persistence.current(),
        )
        assertTrue(
            "removed 1 rule" in result.outputForLlm,
            "verb cites single removal; got: ${result.outputForLlm}",
        )
    }

    // ── Duplicate-match removal ──────────────────────────────

    @Test fun duplicateMatchingRulesAllRemovedInOneCall() = runTest {
        // Marquee duplicate-removal pin: per kdoc,
        // "Multiple persisted rules with the same pair
        // (legitimate when the user clicked Always on the
        // same prompt twice across sessions) all get
        // removed in one call". Drift to "remove first
        // only" would silently leave duplicates.
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(
            rule("fs.write", "/tmp/*"),
            rule("fs.read", "/etc/*"),
            rule("fs.write", "/tmp/*"), // duplicate
            rule("fs.write", "/tmp/*"), // triplicate
        )
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/tmp/*"),
            ctx = context(sid),
        )

        assertEquals(3, result.data.removedRuleCount, "all 3 duplicates removed")
        assertEquals(1, result.data.remainingRuleCount)
        assertContentEquals(
            listOf(rule("fs.read", "/etc/*")),
            persistence.current(),
            "only fs.read remains",
        )
        // Verb form for multi-match.
        assertTrue(
            "removed 3 duplicate rules" in result.outputForLlm,
            "verb cites multi-removal; got: ${result.outputForLlm}",
        )
    }

    // ── Match semantics: exact on both fields ────────────────

    @Test fun matchRequiresExactPermissionAndPatternBoth() = runTest {
        // Pin: drift to "match permission only" would
        // remove the wrong rule. Persistence has TWO rules
        // with same permission but different patterns —
        // we should remove ONLY the matching one.
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(
            rule("fs.write", "/tmp/*"),
            rule("fs.write", "/home/foo"),
        )
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/tmp/*"),
            ctx = context(sid),
        )

        assertEquals(1, result.data.removedRuleCount, "only matching pair removed")
        assertContentEquals(
            listOf(rule("fs.write", "/home/foo")),
            persistence.current(),
            "the OTHER fs.write rule preserved",
        )
    }

    @Test fun matchIsCaseSensitive() = runTest {
        // Pin: == is case-sensitive on String. Drift to
        // "case-insensitive" would silently match
        // "FS.WRITE" against rule with "fs.write".
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(rule("fs.write", "/tmp/*"))
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "FS.WRITE", pattern = "/tmp/*"),
            ctx = context(sid),
        )
        // Different case → no match.
        assertEquals(0, result.data.removedRuleCount)
        assertEquals(0, persistence.saveCallCount, "save not called on case mismatch")
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputCarriesSessionTitleAndAction() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", title = "Test Session")
        val persistence = CapturingPersistence(emptyList())

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/tmp/*"),
            ctx = context(sid),
        )
        assertEquals("s1", result.data.sessionId)
        assertEquals("remove_permission_rule", result.data.action)
        assertEquals("Test Session", result.data.title)
    }

    @Test fun outputForLlmCitesRuleAndRemainingCount() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val initial = listOf(
            rule("fs.read", "/tmp/*"),
            rule("fs.write", "/home/foo"),
        )
        val persistence = CapturingPersistence(initial)

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/home/foo"),
            ctx = context(sid),
        )
        // Body cites permission, pattern, remaining count, and re-grant hint.
        assertTrue("(fs.write, /home/foo)" in result.outputForLlm)
        assertTrue(
            "1 rule(s) remain" in result.outputForLlm,
            "remaining count cited; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Re-grant via the next interactive permission prompt" in result.outputForLlm,
            "re-grant hint cited; got: ${result.outputForLlm}",
        )
    }

    // ── ctx.resolveSessionId fallback ────────────────────────

    @Test fun nullInputSessionIdFallsBackToContextSessionId() = runTest {
        val store = newStore()
        val ctxSid = seedSession(store, "ctx-session")
        val persistence = CapturingPersistence(emptyList())

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(sessionId = null, permission = "x", pattern = "y"),
            ctx = context(ctxSid),
        )
        assertEquals(ctxSid.value, result.data.sessionId)
    }

    // ── ToolResult.title ─────────────────────────────────────

    @Test fun toolResultTitleCitesPermissionAndPattern() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val persistence = CapturingPersistence(emptyList())

        val result = executeRemovePermissionRule(
            sessions = store,
            permissionRulesPersistence = persistence,
            input = input(permission = "fs.write", pattern = "/tmp/*"),
            ctx = context(sid),
        )
        assertTrue("remove_permission_rule" in result.title!!)
        assertTrue("fs.write" in result.title!!)
        assertTrue("/tmp/*" in result.title!!)
    }
}
