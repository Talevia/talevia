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
 * Direct tests for [executeSessionSetSpendCap] —
 * `core/tool/builtin/session/action/SessionSetSpendCapHandler.kt`.
 * The `session_action(action="set_spend_cap")` handler.
 * Sister to cycle 184's SessionSetSystemPromptHandler;
 * cycle 195 audit: 87 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`capCents` value semantics: null = clear cap; 0 =
 *    block all paid; positive = cap value.** Per kdoc:
 *    "capCents = 0 — set cap to 'spend nothing'; every
 *    subsequent paid AIGC call ASKs." Drift to "0
 *    treated as null/clear" would silently re-allow
 *    paid calls when the user wanted them blocked.
 *
 * 2. **Negative capCents rejected via `require`.** The
 *    error message includes a documented dollars-to-cents
 *    hint ("\$5.00 = 500"). Drift to "silently accept
 *    negative" would let agents create sessions with
 *    nonsensical caps.
 *
 * 3. **No-change is a true no-op (no `updateSession`,
 *    no `updatedAt` bump, distinct title prefix).**
 *    Re-setting same cap → result.title is
 *    "set_spend_cap (no-op)" + body says "nothing to do"
 *    + store's updatedAt unchanged. Drift to "always
 *    update" would bump updatedAt unnecessarily on every
 *    re-call.
 */
class SessionSetSpendCapHandlerTest {

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
        spendCapCents: Long? = null,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                spendCapCents = spendCapCents,
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
        capCents: Long?,
    ): SessionActionTool.Input = SessionActionTool.Input(
        action = "set_spend_cap",
        sessionId = sessionId,
        capCents = capCents,
    )

    // ── Negative capCents → throw ────────────────────────────

    @Test fun negativeCapCentsThrowsWithDollarsToCentsHint() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")

        val ex = assertFailsWith<IllegalArgumentException> {
            executeSessionSetSpendCap(
                sessions = store,
                clock = Clock.System,
                input = input(capCents = -100),
                ctx = context(sid),
            )
        }
        assertTrue(
            "must be ≥ 0" in (ex.message ?: ""),
            "non-negative phrase; got: ${ex.message}",
        )
        assertTrue(
            "multiply by 100" in (ex.message ?: "") ||
                "\$5.00 = 500" in (ex.message ?: ""),
            "dollars-to-cents hint; got: ${ex.message}",
        )
        assertTrue(
            "got -100" in (ex.message ?: ""),
            "actual value cited; got: ${ex.message}",
        )
    }

    // ── Missing session → throw ──────────────────────────────

    @Test fun missingSessionThrowsWithDiscoverabilityHint() = runTest {
        val store = newStore()

        val ex = assertFailsWith<IllegalStateException> {
            executeSessionSetSpendCap(
                sessions = store,
                clock = Clock.System,
                input = input(capCents = 100),
                ctx = context(SessionId("ghost")),
            )
        }
        assertTrue("not found" in (ex.message ?: ""))
        assertTrue("session_query(select=sessions)" in (ex.message ?: ""))
    }

    // ── Set cap (null → positive) ────────────────────────────

    @Test fun settingPositiveCapOnUnconfiguredSessionWritesIt() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 500L),
            ctx = context(sid),
        )

        assertEquals(500L, store.getSession(sid)!!.spendCapCents)
        assertNull(result.data.previousSpendCapCents)
        assertEquals(500L, result.data.spendCapCents)
        assertTrue(
            "none → 500¢" in result.outputForLlm,
            "summary cites old → new; got: ${result.outputForLlm}",
        )
    }

    // ── Marquee: cap=0 means "block all" not "clear" ────────

    @Test fun zeroCapMeansBlockAllNotConfusedWithNullClear() = runTest {
        // Marquee 0-vs-null pin: per kdoc "capCents = 0 —
        // set cap to 'spend nothing'; every subsequent paid
        // AIGC call ASKs." Drift to "0 treated as
        // null/clear" would silently re-allow paid calls
        // when user wanted them blocked.
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 0L),
            ctx = context(sid),
        )

        assertEquals(0L, store.getSession(sid)!!.spendCapCents, "cap=0 stored as 0L, NOT null")
        assertEquals(0L, result.data.spendCapCents, "result reflects 0L, NOT null")
        // Summary uses "0¢ (block all)" formatting.
        assertTrue(
            "0¢ (block all)" in result.outputForLlm,
            "block-all formatting; got: ${result.outputForLlm}",
        )
        assertTrue(
            "none → 0¢ (block all)" in result.outputForLlm,
            "summary cites old → new; got: ${result.outputForLlm}",
        )
    }

    // ── Clear cap (positive → null) ──────────────────────────

    @Test fun clearingCapUnsetsToNull() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 1000L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = null),
            ctx = context(sid),
        )

        assertNull(store.getSession(sid)!!.spendCapCents, "cap cleared to null")
        assertEquals(1000L, result.data.previousSpendCapCents)
        assertNull(result.data.spendCapCents)
        assertTrue(
            "1000¢ → none" in result.outputForLlm,
            "summary cites cap → none; got: ${result.outputForLlm}",
        )
    }

    // ── Raise / lower cap ────────────────────────────────────

    @Test fun raisingCapAllowsHigherValue() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 100L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 1000L),
            ctx = context(sid),
        )
        assertEquals(1000L, store.getSession(sid)!!.spendCapCents)
        assertEquals(100L, result.data.previousSpendCapCents)
        assertTrue("100¢ → 1000¢" in result.outputForLlm)
    }

    @Test fun loweringCapAllowsLowerValue() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 1000L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 500L),
            ctx = context(sid),
        )
        assertEquals(500L, store.getSession(sid)!!.spendCapCents)
        assertEquals(1000L, result.data.previousSpendCapCents)
        assertTrue("1000¢ → 500¢" in result.outputForLlm)
    }

    @Test fun loweringCapFromPositiveToZeroBlockAllWorks() = runTest {
        // Pin: legitimate path "I had a $5 cap, switch to
        // block-all." Verifies the 0-vs-clear discrimination
        // in a live cap-setting flow.
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 500L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 0L),
            ctx = context(sid),
        )
        assertEquals(0L, store.getSession(sid)!!.spendCapCents)
        assertEquals(500L, result.data.previousSpendCapCents)
        assertEquals(0L, result.data.spendCapCents)
        assertTrue("500¢ → 0¢ (block all)" in result.outputForLlm)
    }

    // ── No-change idempotency ────────────────────────────────

    @Test fun reSettingSameCapIsNoOp() = runTest {
        // Marquee no-op pin: writing same value as before
        // skips the updateSession call. Verified via
        // future-time clock that would advance updatedAt
        // if updateSession were called.
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 500L)
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        val clockNow = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        val futureClock = object : Clock {
            override fun now(): Instant = clockNow
        }

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = futureClock,
            input = input(capCents = 500L),
            ctx = context(sid),
        )

        // updatedAt unchanged.
        assertEquals(
            updatedAtBefore,
            store.getSession(sid)!!.updatedAt,
            "no-op rewrite does NOT bump updatedAt",
        )
        // Distinct title for no-op result.
        assertTrue(
            "(no-op)" in result.title!!,
            "no-op title; got: ${result.title}",
        )
        // Body cites "nothing to do."
        assertTrue(
            "nothing to do" in result.outputForLlm,
            "body cites no-op; got: ${result.outputForLlm}",
        )
        // Result still echoes both fields.
        assertEquals(500L, result.data.previousSpendCapCents)
        assertEquals(500L, result.data.spendCapCents)
    }

    @Test fun reClearingAlreadyNullCapIsNoOp() = runTest {
        // Same idempotency pin for null → null (already
        // cleared, asked to clear again).
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        val clockNow = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        val futureClock = object : Clock {
            override fun now(): Instant = clockNow
        }

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = futureClock,
            input = input(capCents = null),
            ctx = context(sid),
        )
        assertEquals(updatedAtBefore, store.getSession(sid)!!.updatedAt)
        assertTrue("(no-op)" in result.title!!)
        // No-op body cites cap=none.
        assertTrue(
            "cap=none" in result.outputForLlm,
            "body cites cap=none; got: ${result.outputForLlm}",
        )
    }

    @Test fun reSettingZeroCapIsNoOp() = runTest {
        // Pin: 0 → 0 also no-op (zero is a legitimate
        // value, equality compares 0 == 0).
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 0L)
        val updatedAtBefore = store.getSession(sid)!!.updatedAt

        val clockNow = Instant.fromEpochMilliseconds(2_000_000_000_000L)
        val futureClock = object : Clock {
            override fun now(): Instant = clockNow
        }

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = futureClock,
            input = input(capCents = 0L),
            ctx = context(sid),
        )
        assertEquals(updatedAtBefore, store.getSession(sid)!!.updatedAt)
        assertTrue("(no-op)" in result.title!!)
        // No-op body cites cap=0¢ (block all).
        assertTrue(
            "cap=0¢ (block all)" in result.outputForLlm,
            "body cites block-all in no-op; got: ${result.outputForLlm}",
        )
    }

    // ── formatCap helper output (via outputForLlm) ──────────

    @Test fun formatCapNullRendersAsNone() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = 100L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = null),
            ctx = context(sid),
        )
        assertTrue("→ none" in result.outputForLlm)
    }

    @Test fun formatCapZeroRendersAsBlockAll() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 0L),
            ctx = context(sid),
        )
        assertTrue("0¢ (block all)" in result.outputForLlm)
    }

    @Test fun formatCapPositiveRendersAsNCents() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 250L),
            ctx = context(sid),
        )
        assertTrue("250¢" in result.outputForLlm)
        // Without the "(block all)" marker — drift would
        // mislabel non-zero caps.
        assertTrue(
            "250¢ (block all)" !in result.outputForLlm,
            "non-zero cap NOT marked block-all",
        )
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputCarriesSessionFields() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", title = "Session Title", spendCapCents = 100L)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 500L),
            ctx = context(sid),
        )
        assertEquals("s1", result.data.sessionId)
        assertEquals("set_spend_cap", result.data.action)
        assertEquals("Session Title", result.data.title)
        assertEquals(100L, result.data.previousSpendCapCents)
        assertEquals(500L, result.data.spendCapCents)
    }

    // ── ctx.resolveSessionId fallback ────────────────────────

    @Test fun nullInputSessionIdFallsBackToContextSessionId() = runTest {
        val store = newStore()
        val ctxSid = seedSession(store, "ctx-session", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(sessionId = null, capCents = 100L),
            ctx = context(ctxSid),
        )
        assertEquals("ctx-session", result.data.sessionId)
    }

    // ── ToolResult.title ─────────────────────────────────────

    @Test fun toolResultTitleCitesSessionIdAndVerb() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1", spendCapCents = null)

        val result = executeSessionSetSpendCap(
            sessions = store,
            clock = Clock.System,
            input = input(capCents = 500L),
            ctx = context(sid),
        )
        assertTrue("s1" in result.title!!)
        assertTrue("set session spend cap" in result.title!!)
    }
}
