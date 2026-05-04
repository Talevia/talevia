package io.talevia.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.agent.AgentTurnExecutor
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Defensive-constructor validation pins for two more
 * `init { require(...) }`-bearing classes whose rejection cases
 * had no individual pins as of cycle 324:
 *
 *   - [AgentTurnExecutor] (`init { require(providers.isNotEmpty()) }`)
 *     — every Agent loop's retry-and-fallback chain.
 *   - [SqlDelightSessionStore] (`init { require(maxMessages > 0) }`)
 *     — the hard upper bound on session.messages.size that catches
 *     compactor-overflow scenarios per the kdoc.
 *
 * Cycle 325 closes these two gaps. Same audit-pattern as cycles
 * 321-324: every load-bearing constructor `require` deserves a
 * paired test that exercises the rejection path.
 *
 * Why this matters for AgentTurnExecutor: without the require, an
 * empty providers list slips through construction. The retry loop
 * later does `providers[index].stream(request)` which would throw
 * IndexOutOfBoundsException on the FIRST turn — far from the
 * misconfiguration source. The require fails loud at construction,
 * pinning it prevents a future refactor from silently allowing
 * empty.
 *
 * Why this matters for SqlDelightSessionStore: maxMessages is the
 * load-bearing safety net behind the Compactor's soft trigger.
 * Pre-cycle the require already exists; pinning the rejection path
 * prevents drift to "0 = unbounded" magic-number semantics that
 * would silently disable the safety net.
 */
class AgentTurnExecutorAndSessionStoreValidationTest {

    // ── AgentTurnExecutor.providers non-empty ──────────────────────

    @Test fun agentTurnExecutorRejectsEmptyProviders() {
        // The require check fires at construction — before any
        // Agent.run call. Without it, the retry loop would
        // IndexOutOfBoundsException on the first turn (provider[0]
        // unreachable on an empty list).
        assertFailsWith<IllegalArgumentException> {
            AgentTurnExecutor(
                providers = emptyList(),
                registry = ToolRegistry(),
                permissions = AllowAllPermissionService(),
                store = inMemorySessionStore(),
                bus = EventBus(),
                clock = Clock.System,
                metrics = null,
                systemPrompt = null,
            )
        }
    }

    // ── SqlDelightSessionStore.maxMessages > 0 ─────────────────────

    @Test fun sessionStoreRejectsZeroMaxMessages() {
        // Strict-positive: 0 means "no messages ever stored",
        // pathological. Pin: drift to `>=` (which would let 0 pass)
        // would silently disable session storage.
        assertFailsWith<IllegalArgumentException> {
            SqlDelightSessionStore(
                db = inMemoryDb(),
                bus = EventBus(),
                maxMessages = 0,
            )
        }
    }

    @Test fun sessionStoreRejectsNegativeMaxMessages() {
        // Negative maxMessages would yield `current >= maxMessages`
        // (true for any current >= 0) on every appendMessage,
        // immediately throwing SessionFull on the first message.
        // Pathological — fail loud at construction.
        assertFailsWith<IllegalArgumentException> {
            SqlDelightSessionStore(
                db = inMemoryDb(),
                bus = EventBus(),
                maxMessages = -1,
            )
        }
    }

    @Test fun sessionStoreAcceptsMinimalPositiveMaxMessages() {
        // Boundary: 1 is the minimum valid value (stores exactly one
        // message before triggering SessionFull). Anti-pin against
        // a refactor tightening to `>= 100` or similar production
        // floor — single-message sessions are degenerate but
        // legitimate (test rigs that probe the SessionFull path).
        SqlDelightSessionStore(
            db = inMemoryDb(),
            bus = EventBus(),
            maxMessages = 1,
        )
    }

    @Test fun sessionStoreAcceptsDefaultMaxMessages() {
        // Default DEFAULT_MAX_MESSAGES (1000) is well-inside the
        // range. Anti-pin against drift in the default constant.
        SqlDelightSessionStore(
            db = inMemoryDb(),
            bus = EventBus(),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun inMemoryDb(): TaleviaDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return TaleviaDb(driver)
    }

    private fun inMemorySessionStore(): SqlDelightSessionStore =
        SqlDelightSessionStore(db = inMemoryDb(), bus = EventBus())
}
