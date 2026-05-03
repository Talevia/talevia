package io.talevia.core.tool.builtin.session.action

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
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [executeSessionSetSystemPrompt] —
 * `core/tool/builtin/session/action/SessionSetSystemPromptHandler.kt`.
 * The `session_action(action="set_system_prompt")` handler. Cycle 184
 * audit: 65 LOC, 0 direct test refs (the handler is exercised
 * through the full SessionActionTool integration but the
 * value-vs-null-vs-empty distinction in
 * `systemPromptOverride`, the 5-arm verb-message branching,
 * and the `changed → updateSession` skip-on-no-change semantics
 * were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`systemPromptOverride` distinguishes null from empty
 *    string.** Per kdoc: "empty string is a legitimate
 *    'run with no system prompt' override and is NOT
 *    conflated with null." Drift to "empty becomes null"
 *    would silently re-enable the Agent's constructor
 *    default when the user wanted NO prompt. Two write
 *    states + 1 clear state = 3 distinct semantics that
 *    must round-trip cleanly.
 *
 * 2. **No-change is a true no-op (no `updateSession` call).**
 *    Per impl: `if (changed) sessions.updateSession(...)`.
 *    Drift to "always update" would bump `updatedAt`
 *    unnecessarily on every re-call (visible in session
 *    timeline / staleness checks). Pinned by checking
 *    `session.updatedAt` is unchanged after a same-value
 *    re-call.
 *
 * 3. **Verb message has 5 arms across (changed, next-shape)
 *    states**: no-override-unchanged, override-unchanged,
 *    override-cleared, override-set-to-empty, override-set-
 *    to-N-char. The agent reads outputForLlm to confirm
 *    what happened — drift in any arm would mislead.
 */
class SessionSetSystemPromptHandlerTest {

    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
        title: String = "test session",
        systemPromptOverride: String? = null,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                systemPromptOverride = systemPromptOverride,
                createdAt = baseTime,
                updatedAt = baseTime,
            ),
        )
        return sessionId
    }

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
        systemPromptOverride: String?,
    ): SessionActionTool.Input = SessionActionTool.Input(
        action = "set_system_prompt",
        sessionId = sessionId,
        systemPromptOverride = systemPromptOverride,
    )

    // ── Missing session → throw ──────────────────────────────

    @Test fun missingSessionThrowsWithDiscoverabilityHint() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            executeSessionSetSystemPrompt(
                sessions = store,
                clock = Clock.System,
                input = input(systemPromptOverride = "hello"),
                ctx = context(SessionId("ghost")),
            )
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "not-found phrase; got: ${ex.message}",
        )
        assertTrue(
            "session_query(select=sessions)" in (ex.message ?: ""),
            "discoverability hint; got: ${ex.message}",
        )
    }

    // ── Set override (null → non-empty) ──────────────────────

    @Test fun settingNonEmptyOverrideOnUnconfiguredSessionWritesIt() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)

        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "you are a kotlin expert"),
            ctx = context(sid),
        )

        // Store reflects the new override.
        assertEquals(
            "you are a kotlin expert",
            store.getSession(sid)!!.systemPromptOverride,
        )
        // Result data carries previous=null, new=string.
        assertNull(result.data.previousSystemPromptOverride)
        assertEquals("you are a kotlin expert", result.data.newSystemPromptOverride)
        // Verb cites the prompt LENGTH (the 23-char count
        // — drift to citing the prompt content would leak
        // sensitive system-prompt text into outputForLlm).
        assertTrue(
            "23-char" in result.outputForLlm,
            "verb cites length; got: ${result.outputForLlm}",
        )
        assertTrue(
            "the new override" in result.outputForLlm,
            "outputForLlm cites 'next turn will use'; got: ${result.outputForLlm}",
        )
    }

    // ── Set override to empty string (legitimate "no system prompt") ──

    @Test fun emptyStringIsLegitimateOverrideNotConfusedWithNull() = runTest {
        // Marquee "" vs null discriminator pin: per kdoc
        // "empty string is a legitimate 'run with no
        // system prompt' override and is NOT conflated
        // with null." Drift to "empty becomes null" would
        // silently re-enable the Agent's default when
        // user wanted NO prompt.
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)

        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = ""),
            ctx = context(sid),
        )

        assertEquals("", store.getSession(sid)!!.systemPromptOverride)
        assertNull(result.data.previousSystemPromptOverride)
        assertEquals(
            "",
            result.data.newSystemPromptOverride,
            "newSystemPromptOverride is the empty string, NOT null",
        )
        assertTrue(
            "empty string" in result.outputForLlm,
            "verb cites empty-string semantics; got: ${result.outputForLlm}",
        )
        // outputForLlm cites "the new override" (next turn
        // will use the empty override, NOT the default).
        assertTrue(
            "the new override" in result.outputForLlm,
        )
    }

    // ── Clear override (non-null → null) ─────────────────────

    @Test fun clearingOverrideUnsetsToNull() = runTest {
        val store = newStore()
        val sid = seedSession(
            store,
            "s1",
            systemPromptOverride = "you are a kotlin expert",
        )

        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = null),
            ctx = context(sid),
        )

        assertNull(
            store.getSession(sid)!!.systemPromptOverride,
            "store reflects cleared override",
        )
        assertEquals(
            "you are a kotlin expert",
            result.data.previousSystemPromptOverride,
        )
        assertNull(result.data.newSystemPromptOverride)
        assertTrue(
            "override cleared" in result.outputForLlm,
            "verb cites 'cleared'; got: ${result.outputForLlm}",
        )
        // outputForLlm says next turn uses Agent default
        // (NOT empty / NOT new override).
        assertTrue(
            "Agent default" in result.outputForLlm,
            "outputForLlm cites Agent default; got: ${result.outputForLlm}",
        )
    }

    // ── No-change idempotency ────────────────────────────────

    @Test fun reSettingSameNonEmptyOverrideIsNoOp() = runTest {
        // Marquee no-op pin: writing same value as before
        // skips the updateSession call. Drift to "always
        // update" would bump updatedAt unnecessarily.
        val store = newStore()
        val sid = seedSession(
            store,
            "s1",
            systemPromptOverride = "stay the same",
        )
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        // Use a clock that returns a different time —
        // this would surface drift if updateSession were
        // called (updatedAt would advance to clockNow).
        val clockNow = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        val futureClock = object : Clock {
            override fun now(): Instant = clockNow
        }

        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = futureClock,
            input = input(systemPromptOverride = "stay the same"),
            ctx = context(sid),
        )

        // Session.updatedAt is unchanged (no
        // updateSession call).
        assertEquals(
            updatedAtBefore,
            store.getSession(sid)!!.updatedAt,
            "no-op rewrite does NOT bump updatedAt",
        )
        // Verb message is "override unchanged" — drift
        // to "set to N-char" would mislead the agent.
        assertTrue(
            "override unchanged" in result.outputForLlm,
            "verb cites unchanged; got: ${result.outputForLlm}",
        )
    }

    @Test fun reClearingAlreadyClearedOverrideIsNoOp() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)
        val updatedAtBefore = store.getSession(sid)!!.updatedAt
        val clockNow = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        val futureClock = object : Clock {
            override fun now(): Instant = clockNow
        }

        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = futureClock,
            input = input(systemPromptOverride = null),
            ctx = context(sid),
        )

        assertEquals(
            updatedAtBefore,
            store.getSession(sid)!!.updatedAt,
            "no-op clear-when-already-cleared does NOT bump updatedAt",
        )
        // Verb is "no override (unchanged)" — distinct
        // from "override unchanged" because there's no
        // override to be unchanged WITH.
        assertTrue(
            "no override" in result.outputForLlm,
            "verb cites no-override; got: ${result.outputForLlm}",
        )
        assertTrue(
            "(unchanged)" in result.outputForLlm,
            "verb cites unchanged; got: ${result.outputForLlm}",
        )
        // When next == null, outputForLlm should still
        // cite Agent default for the "next turn will use".
        assertTrue(
            "Agent default" in result.outputForLlm,
        )
    }

    // ── 5-verb arm coverage ─────────────────────────────────

    @Test fun verbArm1NoOverrideUnchanged() = runTest {
        // Pin: !changed && next == null → "no override (unchanged)"
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = null),
            ctx = context(sid),
        )
        assertTrue("no override (unchanged)" in result.outputForLlm)
    }

    @Test fun verbArm2OverrideUnchanged() = runTest {
        // Pin: !changed && next != null → "override unchanged"
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = "x")
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "x"),
            ctx = context(sid),
        )
        assertTrue("override unchanged" in result.outputForLlm)
    }

    @Test fun verbArm3OverrideCleared() = runTest {
        // Pin: changed && next == null → "override cleared"
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = "x")
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = null),
            ctx = context(sid),
        )
        assertTrue("override cleared" in result.outputForLlm)
    }

    @Test fun verbArm4OverrideSetToEmpty() = runTest {
        // Pin: changed && next.isEmpty() → "override set
        // to empty string (no system prompt)"
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = ""),
            ctx = context(sid),
        )
        assertTrue("empty string (no system prompt)" in result.outputForLlm)
    }

    @Test fun verbArm5OverrideSetToNCharPrompt() = runTest {
        // Pin: changed && next.isNotEmpty() → "override
        // set to N-char prompt"
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "abc"),
            ctx = context(sid),
        )
        assertTrue(
            "3-char prompt" in result.outputForLlm,
            "verb cites length; got: ${result.outputForLlm}",
        )
    }

    // ── ctx.resolveSessionId fallback ────────────────────────

    @Test fun nullInputSessionIdFallsBackToContextSessionId() = runTest {
        // Pin: per ctx.resolveSessionId(input.sessionId)
        // — null falls back to the dispatching ctx's
        // sessionId. Drift to "throw on null sessionId"
        // would break the documented "agent's own session
        // is the implicit subject" UX.
        val store = newStore()
        val sid = seedSession(store, "s1", systemPromptOverride = null)
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            // Note: input.sessionId = null deliberately.
            input = input(sessionId = null, systemPromptOverride = "a"),
            ctx = context(sid),
        )
        assertEquals("s1", result.data.sessionId, "ctx.sessionId used as fallback")
        assertEquals("a", store.getSession(sid)!!.systemPromptOverride)
    }

    @Test fun explicitInputSessionIdIsHonoured() = runTest {
        // Pin: explicit input.sessionId wins over ctx.sessionId.
        val store = newStore()
        seedSession(store, "explicit", systemPromptOverride = null)
        seedSession(store, "ctx-fallback", systemPromptOverride = null)
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(sessionId = "explicit", systemPromptOverride = "a"),
            ctx = context(SessionId("ctx-fallback")),
        )
        assertEquals("explicit", result.data.sessionId)
        assertEquals("a", store.getSession(SessionId("explicit"))!!.systemPromptOverride)
        // ctx-fallback session UNCHANGED (drift to "always
        // use ctx" would mutate the wrong session).
        assertNull(store.getSession(SessionId("ctx-fallback"))!!.systemPromptOverride)
    }

    // ── Output.title echoes ───────────────────────────────────

    @Test fun outputTitleEchoesSessionTitle() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", title = "Brand New Session")
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "x"),
            ctx = context(sid),
        )
        assertEquals("Brand New Session", result.data.title, "session.title echoed in output")
        assertTrue(
            "'Brand New Session'" in result.outputForLlm,
            "outputForLlm cites session title; got: ${result.outputForLlm}",
        )
    }

    @Test fun outputActionIsSetSystemPrompt() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "x"),
            ctx = context(sid),
        )
        assertEquals("set_system_prompt", result.data.action)
    }

    // ── ToolResult.title format ─────────────────────────────

    @Test fun toolResultTitleCitesSessionId() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val result = executeSessionSetSystemPrompt(
            sessions = store,
            clock = Clock.System,
            input = input(systemPromptOverride = "x"),
            ctx = context(sid),
        )
        assertTrue(
            "s1" in result.title!!,
            "title cites sessionId; got: ${result.title}",
        )
        assertTrue(
            "system prompt" in result.title!!,
            "title cites verb; got: ${result.title}",
        )
    }
}
