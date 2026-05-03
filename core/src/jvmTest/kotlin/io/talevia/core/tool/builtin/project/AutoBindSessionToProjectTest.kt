package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [autoBindSessionToProject] —
 * `core/tool/builtin/project/AutoBindSessionToProject.kt`.
 * The internal-rebind helper that bypasses
 * `SwitchProjectTool`'s mid-run guard so
 * `project_lifecycle_action create` /
 * `create_from_template` can atomically bind the
 * dispatching session to the freshly-minted project.
 * Cycle 178 audit: 59 LOC, 0 direct test refs (used
 * indirectly through CreateProjectHandler /
 * CreateFromTemplateHandler integration tests but the
 * branch-level contracts — null-store no-op, missing-
 * session no-op, same-id idempotent skip, different-id
 * update + bus event — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Three early-return cases: `sessions == null`,
 *    session-not-found, same-id idempotent.** Each
 *    branch must NOT publish a bus event AND NOT mutate
 *    the session. Drift in any of the three (e.g. drift
 *    to "publish even on no-op") would have UI / metrics
 *    sinks see phantom binding-changes that confuse the
 *    user and waste budget.
 *
 * 2. **Different-id mutates session AND publishes
 *    `BusEvent.SessionProjectBindingChanged`.** Both
 *    side-effects must happen together. Drift to "mutate
 *    without publish" would leave subscribers stale; drift
 *    to "publish without mutate" would have the bus event
 *    say one thing but the session say another.
 *
 * 3. **The published event carries `previousProjectId =
 *    session.currentProjectId BEFORE mutation` and
 *    `newProjectId = projectId`.** Drift in either
 *    direction would mislead "what changed?" diagnostics
 *    that the bus event is the LLM-visible signal for.
 */
class AutoBindSessionToProjectTest {

    private val now: Instant = Clock.System.now()

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        sid: String,
        currentProjectId: ProjectId? = null,
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("seed-project"),
                title = "test",
                currentProjectId = currentProjectId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sessionId
    }

    private fun context(
        sessionId: SessionId,
        capturedEvents: MutableList<BusEvent>,
    ): ToolContext = ToolContext(
        sessionId = sessionId,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* no-op */ },
        messages = emptyList(),
        publishEvent = { e -> capturedEvents += e },
    )

    // ── null sessions store → no-op ────────────────────────────

    @Test fun nullSessionStoreReturnsImmediatelyNoOp() = runTest {
        // Pin: sessions == null → early return. No bus
        // event, no mutation. Used by test rigs / legacy
        // compositions without a session store.
        val captured = mutableListOf<BusEvent>()
        autoBindSessionToProject(
            sessions = null,
            clock = Clock.System,
            ctx = context(SessionId("s"), captured),
            projectId = ProjectId("new-project"),
        )
        assertTrue(captured.isEmpty(), "no event published when sessions is null")
    }

    // ── session not found → no-op ─────────────────────────────

    @Test fun missingSessionReturnsNoOp() = runTest {
        // Pin: session not in store → early return. No bus
        // event, no mutation. Drift to "publish anyway"
        // would emit phantom bus events for non-existent
        // sessions.
        val store = newStore()
        // Don't seed any session — the lookup will return null.
        val captured = mutableListOf<BusEvent>()
        autoBindSessionToProject(
            sessions = store,
            clock = Clock.System,
            ctx = context(SessionId("ghost-session"), captured),
            projectId = ProjectId("new-project"),
        )
        assertTrue(
            captured.isEmpty(),
            "no event published when session is not in store",
        )
    }

    // ── same-id → idempotent no-op ────────────────────────────

    @Test fun sameProjectIdAsCurrentBindingReturnsNoOp() = runTest {
        // Marquee idempotency pin: a session already bound
        // to projectId X, told to bind to X again, takes
        // the early return. No bus event, no mutation.
        // This handles "idempotent re-create with
        // projectId echoing the current binding" (per
        // kdoc).
        val store = newStore()
        val sid = seedSession(store, "s1", currentProjectId = ProjectId("same-project"))
        val sessionBefore = store.getSession(sid)!!
        val captured = mutableListOf<BusEvent>()

        autoBindSessionToProject(
            sessions = store,
            clock = Clock.System,
            ctx = context(sid, captured),
            projectId = ProjectId("same-project"),
        )

        // No event published — drift to "always publish"
        // would emit phantom binding-changes.
        assertTrue(
            captured.isEmpty(),
            "no event when projectId matches current binding",
        )
        // Session unchanged — updatedAt did NOT bump.
        val sessionAfter = store.getSession(sid)!!
        assertEquals(
            sessionBefore.updatedAt,
            sessionAfter.updatedAt,
            "updatedAt unchanged on idempotent rebind",
        )
        assertEquals(
            sessionBefore.currentProjectId,
            sessionAfter.currentProjectId,
            "currentProjectId unchanged on idempotent rebind",
        )
    }

    // ── different-id → mutate + publish ───────────────────────

    @Test fun differentProjectIdMutatesSessionAndPublishesEvent() = runTest {
        // Marquee mutation+publish pin: when projectId
        // differs from previous binding, BOTH the session
        // is updated AND the bus event is published. Drift
        // to "skip mutation" would leave currentProjectId
        // stale; drift to "skip publish" would leave
        // subscribers blind to the rebind.
        val store = newStore()
        val sid = seedSession(store, "s1", currentProjectId = ProjectId("old-project"))
        val captured = mutableListOf<BusEvent>()

        autoBindSessionToProject(
            sessions = store,
            clock = Clock.System,
            ctx = context(sid, captured),
            projectId = ProjectId("new-project"),
        )

        // Session mutated.
        val sessionAfter = store.getSession(sid)!!
        assertEquals(
            ProjectId("new-project"),
            sessionAfter.currentProjectId,
            "currentProjectId now points at new-project",
        )
        // Bus event published (exactly one).
        assertEquals(1, captured.size, "exactly one bus event published")
        val event = captured.single() as BusEvent.SessionProjectBindingChanged
        assertEquals(sid, event.sessionId)
        assertEquals(
            ProjectId("old-project"),
            event.previousProjectId,
            "previous binding captured BEFORE mutation",
        )
        assertEquals(
            ProjectId("new-project"),
            event.newProjectId,
            "newProjectId echoes the requested projectId",
        )
    }

    @Test fun firstBindingFromNullPreviousPublishesNullPrevious() = runTest {
        // Pin: a session that's never been bound (currentProjectId
        // = null) → previousProjectId in the event is null,
        // not omitted. Drift to "skip null previous" would
        // mean subscribers can't distinguish "first bind"
        // from "no event."
        val store = newStore()
        val sid = seedSession(store, "s1", currentProjectId = null)
        val captured = mutableListOf<BusEvent>()

        autoBindSessionToProject(
            sessions = store,
            clock = Clock.System,
            ctx = context(sid, captured),
            projectId = ProjectId("first-project"),
        )

        val sessionAfter = store.getSession(sid)!!
        assertEquals(ProjectId("first-project"), sessionAfter.currentProjectId)
        val event = captured.single() as BusEvent.SessionProjectBindingChanged
        assertNull(event.previousProjectId, "previous is null on first bind")
        assertEquals(ProjectId("first-project"), event.newProjectId)
    }

    @Test fun updatedAtIsStampedFromInjectedClock() = runTest {
        // Pin: updatedAt comes from the injected `clock`
        // parameter, NOT from `Clock.System.now()` directly.
        // Drift to "always Clock.System.now()" would break
        // tests + reproducibility — auto-binds in a
        // deterministic test harness MUST use the test's
        // virtual clock.
        val store = newStore()
        val sid = seedSession(store, "s1", currentProjectId = null)
        val captured = mutableListOf<BusEvent>()
        // Fixed clock that returns a fixed instant.
        val fixedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val fixedClock = object : Clock {
            override fun now(): Instant = fixedAt
        }

        autoBindSessionToProject(
            sessions = store,
            clock = fixedClock,
            ctx = context(sid, captured),
            projectId = ProjectId("new-project"),
        )

        val sessionAfter = store.getSession(sid)!!
        assertEquals(
            fixedAt,
            sessionAfter.updatedAt,
            "updatedAt stamped from injected clock",
        )
    }

    // ── ProjectId equality is by .value, not reference ────────

    @Test fun sameProjectIdValueButDifferentInstanceIsStillNoOp() = runTest {
        // Pin: per the impl `if (previous?.value ==
        // projectId.value) return`. Two ProjectId instances
        // with the same `.value` string are treated as
        // same-id (idempotent path). Drift to reference-
        // equality would emit phantom events on every
        // construction-fresh ProjectId.
        val store = newStore()
        val sid = seedSession(store, "s1", currentProjectId = ProjectId("p-shared"))
        val captured = mutableListOf<BusEvent>()

        autoBindSessionToProject(
            sessions = store,
            clock = Clock.System,
            ctx = context(sid, captured),
            // Fresh ProjectId instance with the same value.
            projectId = ProjectId("p-shared"),
        )

        assertTrue(
            captured.isEmpty(),
            "value-equal ProjectId triggers idempotent no-op",
        )
    }
}
