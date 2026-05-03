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
import kotlin.test.assertTrue

/**
 * Direct tests for [executeSessionSetToolEnabled] —
 * `core/tool/builtin/session/action/SessionSetToolEnabledHandler.kt`.
 * The `session_action(action="set_tool_enabled")` handler.
 * Sister to cycles 184/195 (set_system_prompt /
 * set_spend_cap). Cycle 198 audit: 99 LOC, 0 direct test
 * refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Upsert semantics: enabled=false → adds toolId to
 *    disabledToolIds; enabled=true → removes.** Per kdoc
 *    §3a #2: "no define_/update_ split" — the same verb
 *    handles both enable and disable. Drift to "only
 *    handles enable" or "only handles disable" would
 *    break the agent's "stop using X" / "use X again"
 *    UX.
 *
 * 2. **Idempotency: no-op when state already matches.**
 *    Both directions: re-disabling already-disabled is
 *    no-op; re-enabling already-enabled is no-op. Result
 *    carries `toolEnabledChanged = false`. Drift to
 *    "always update" would bump updatedAt unnecessarily.
 *
 * 3. **Does NOT validate `toolId` against live registry.**
 *    Per kdoc: "the disabled set is per-session
 *    persisted state and may legitimately reference an
 *    env-gated tool that isn't loaded right now (it'll
 *    still be filtered out if it's ever loaded)." Drift
 *    to "registry-check" would break per-session
 *    disabled state for env-gated tools.
 */
class SessionSetToolEnabledHandlerTest {

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
        disabledToolIds: Set<String> = emptySet(),
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                disabledToolIds = disabledToolIds,
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
        toolId: String?,
        enabled: Boolean?,
    ): SessionActionTool.Input = SessionActionTool.Input(
        action = "set_tool_enabled",
        sessionId = sessionId,
        toolId = toolId,
        enabled = enabled,
    )

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingToolIdThrows() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val ex = assertFailsWith<IllegalArgumentException> {
            executeSessionSetToolEnabled(
                sessions = store,
                clock = Clock.System,
                input = input(toolId = null, enabled = false),
                ctx = context(sid),
            )
        }
        assertTrue(
            "requires non-blank `toolId`" in (ex.message ?: ""),
            "expected requires phrase; got: ${ex.message}",
        )
    }

    @Test fun blankToolIdThrows() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        assertFailsWith<IllegalArgumentException> {
            executeSessionSetToolEnabled(
                sessions = store,
                clock = Clock.System,
                input = input(toolId = "   ", enabled = false),
                ctx = context(sid),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            executeSessionSetToolEnabled(
                sessions = store,
                clock = Clock.System,
                input = input(toolId = "", enabled = false),
                ctx = context(sid),
            )
        }
    }

    @Test fun missingEnabledThrowsWithDocumentedHint() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        val ex = assertFailsWith<IllegalStateException> {
            executeSessionSetToolEnabled(
                sessions = store,
                clock = Clock.System,
                input = input(toolId = "fs.write", enabled = null),
                ctx = context(sid),
            )
        }
        assertTrue(
            "requires `enabled`" in (ex.message ?: ""),
            "requires phrase; got: ${ex.message}",
        )
        assertTrue(
            "true to enable" in (ex.message ?: "") &&
                "false to disable" in (ex.message ?: ""),
            "documented hint cited; got: ${ex.message}",
        )
    }

    @Test fun missingSessionThrowsWithDiscoverabilityHint() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            executeSessionSetToolEnabled(
                sessions = store,
                clock = Clock.System,
                input = input(toolId = "fs.write", enabled = false),
                ctx = context(SessionId("ghost")),
            )
        }
        assertTrue("not found" in (ex.message ?: ""))
        assertTrue("session_query(select=sessions)" in (ex.message ?: ""))
    }

    // ── Disable: enabled=false → add to disabled set ────────

    @Test fun disableFreshToolAddsToDisabledSet() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = emptySet())

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "generate_image", enabled = false),
            ctx = context(sid),
        )

        assertEquals(setOf("generate_image"), store.getSession(sid)!!.disabledToolIds)
        assertEquals(true, result.data.toolEnabledChanged, "actual change → toolEnabledChanged=true")
        assertEquals("generate_image", result.data.toolId)
        assertEquals(false, result.data.enabled)
        assertTrue(
            "Hidden from next turn's tool spec" in result.outputForLlm,
            "body cites hidden; got: ${result.outputForLlm}",
        )
    }

    @Test fun disableToolAddsToExistingDisabledSet() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = setOf("existing_tool"))

        executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "new_tool", enabled = false),
            ctx = context(sid),
        )

        assertEquals(
            setOf("existing_tool", "new_tool"),
            store.getSession(sid)!!.disabledToolIds,
            "new tool added; existing preserved",
        )
    }

    // ── Enable: enabled=true → remove from disabled set ─────

    @Test fun enableDisabledToolRemovesFromDisabledSet() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = setOf("generate_image", "other_tool"))

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "generate_image", enabled = true),
            ctx = context(sid),
        )

        assertEquals(setOf("other_tool"), store.getSession(sid)!!.disabledToolIds)
        assertEquals(true, result.data.toolEnabledChanged)
        assertTrue(
            "Visible in next turn's tool spec" in result.outputForLlm,
            "body cites visible; got: ${result.outputForLlm}",
        )
    }

    // ── Idempotency: re-enable already-enabled = no-op ──────

    @Test fun reEnablingAlreadyEnabledToolIsNoOp() = runTest {
        // Marquee idempotency pin: re-enable when toolId
        // is NOT in disabledToolIds → no-op. Drift to
        // "always update" would bump updatedAt
        // unnecessarily.
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = emptySet())
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        val futureClock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        }

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = futureClock,
            input = input(toolId = "fs.write", enabled = true),
            ctx = context(sid),
        )

        assertEquals(
            updatedAtBefore,
            store.getSession(sid)!!.updatedAt,
            "no-op: updatedAt NOT bumped",
        )
        assertEquals(false, result.data.toolEnabledChanged, "toolEnabledChanged=false on no-op")
        assertTrue(
            "(no-op)" in result.title!!,
            "(no-op) title; got: ${result.title}",
        )
        assertTrue(
            "already enabled" in result.outputForLlm,
            "body cites already-enabled; got: ${result.outputForLlm}",
        )
        assertTrue(
            "nothing to do" in result.outputForLlm,
            "body cites nothing-to-do; got: ${result.outputForLlm}",
        )
    }

    @Test fun reDisablingAlreadyDisabledToolIsNoOp() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = setOf("fs.write"))
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        val futureClock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        }

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = futureClock,
            input = input(toolId = "fs.write", enabled = false),
            ctx = context(sid),
        )

        assertEquals(
            updatedAtBefore,
            store.getSession(sid)!!.updatedAt,
            "no-op: updatedAt NOT bumped",
        )
        assertEquals(false, result.data.toolEnabledChanged)
        assertTrue("(no-op)" in result.title!!)
        assertTrue(
            "already disabled" in result.outputForLlm,
            "body cites already-disabled; got: ${result.outputForLlm}",
        )
    }

    // ── No-registry-validation pin ──────────────────────────

    @Test fun unknownToolIdNotValidatedAgainstLiveRegistry() = runTest {
        // Marquee no-registry-check pin: per kdoc "the
        // disabled set is per-session persisted state and
        // may legitimately reference an env-gated tool
        // that isn't loaded right now." A tool id that
        // would NOT match any registered tool still gets
        // added to the disabled set — drift to "validate
        // against registry" would break this.
        val store = newStore()
        val sid = seedSession(store, "s1")

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "env_gated_tool_not_in_registry", enabled = false),
            ctx = context(sid),
        )
        // Tool still added to disabled set despite
        // never being loaded.
        assertEquals(
            setOf("env_gated_tool_not_in_registry"),
            store.getSession(sid)!!.disabledToolIds,
        )
        assertEquals(true, result.data.toolEnabledChanged)
    }

    // ── Title format ────────────────────────────────────────

    @Test fun toolResultTitleCitesVerbToolIdAndSession() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")

        val resultDisabled = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "generate_image", enabled = false),
            ctx = context(sid),
        )
        assertTrue("disabled generate_image for s1" in resultDisabled.title!!)
    }

    @Test fun toolResultTitleForEnableCitesEnabledVerb() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = setOf("generate_image"))

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "generate_image", enabled = true),
            ctx = context(sid),
        )
        assertTrue("enabled generate_image for s1" in result.title!!)
    }

    // ── Output shape ────────────────────────────────────────

    @Test fun outputCarriesAllFields() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", title = "Test Session")

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "fs.write", enabled = false),
            ctx = context(sid),
        )
        assertEquals("s1", result.data.sessionId)
        assertEquals("set_tool_enabled", result.data.action)
        assertEquals("Test Session", result.data.title)
        assertEquals("fs.write", result.data.toolId)
        assertEquals(false, result.data.enabled)
        assertEquals(true, result.data.toolEnabledChanged)
    }

    // ── ctx.resolveSessionId fallback ───────────────────────

    @Test fun nullInputSessionIdFallsBackToContext() = runTest {
        val store = newStore()
        val ctxSid = seedSession(store, "ctx-sid")

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(sessionId = null, toolId = "fs.write", enabled = false),
            ctx = context(ctxSid),
        )
        assertEquals("ctx-sid", result.data.sessionId)
    }

    // ── Body format on change ───────────────────────────────

    @Test fun bodyFormatOnDisableCitesNextTurnSpec() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "fs.write", enabled = false),
            ctx = context(sid),
        )
        assertTrue("Session s1: fs.write → disabled" in result.outputForLlm)
        assertTrue("Hidden from next turn's tool spec" in result.outputForLlm)
        assertTrue(
            "Visible in next turn's tool spec" !in result.outputForLlm,
            "disable body must NOT cite visibility",
        )
    }

    @Test fun bodyFormatOnEnableCitesNextTurnSpec() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", disabledToolIds = setOf("fs.write"))

        val result = executeSessionSetToolEnabled(
            sessions = store,
            clock = Clock.System,
            input = input(toolId = "fs.write", enabled = true),
            ctx = context(sid),
        )
        assertTrue("Session s1: fs.write → enabled" in result.outputForLlm)
        assertTrue("Visible in next turn's tool spec" in result.outputForLlm)
        assertTrue(
            "Hidden from next turn's tool spec" !in result.outputForLlm,
            "enable body must NOT cite hiding",
        )
    }
}
