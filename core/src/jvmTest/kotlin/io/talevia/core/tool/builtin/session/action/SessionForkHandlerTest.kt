package io.talevia.core.tool.builtin.session.action

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [executeSessionFork] —
 * `core/tool/builtin/session/action/SessionForkHandler.kt`.
 * The `session_action(action="fork")` handler — branches a
 * parent session into a new sibling that points at it via
 * `parentId`. Cycle 191 audit: 77 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`anchorMessageId` semantics: blank/null → full
 *    history copied; non-blank → only at-or-before the
 *    anchor.** The marquee fork-shape pin. Drift to
 *    "blank treated as literal id" would fail the
 *    "fork everything" path when the agent passes "".
 *
 * 2. **`newTitle` semantics: blank/null → store default
 *    "<parent> (fork)"; non-blank → use provided.** Drift
 *    to "use empty string verbatim" would silently create
 *    sessions with whitespace titles.
 *
 * 3. **Output carries forkedSessionId + forkCopiedMessageCount;
 *    `summary` cites both sessions + anchor note when
 *    present + copy count.** The agent reads the summary
 *    to confirm what happened. Drift in any of the 3
 *    output fields would mislead.
 */
class SessionForkHandlerTest {

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
        title: String = "session-$sid",
    ): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = title,
                createdAt = baseTime,
                updatedAt = baseTime,
            ),
        )
        return sessionId
    }

    private suspend fun seedMessage(
        store: SqlDelightSessionStore,
        sid: String,
        mid: String,
        createdAt: Instant = baseTime,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId(mid),
                sessionId = SessionId(sid),
                createdAt = createdAt,
                agent = "test",
                model = ModelRef("anthropic", "claude"),
            ),
        )
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
        anchorMessageId: String? = null,
        newTitle: String? = null,
    ): SessionActionTool.Input = SessionActionTool.Input(
        action = "fork",
        sessionId = sessionId,
        anchorMessageId = anchorMessageId,
        newTitle = newTitle,
    )

    // ── Missing parent session → throw ───────────────────────

    @Test fun missingParentSessionThrowsWithDiscoverabilityHint() = runTest {
        val store = newStore()
        val ex = assertFailsWith<IllegalStateException> {
            executeSessionFork(
                sessions = store,
                input = input(),
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

    // ── anchorMessageId semantics ────────────────────────────

    @Test fun nullAnchorCopiesFullHistory() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent")
        seedMessage(store, "parent", mid = "m1", createdAt = baseTime)
        seedMessage(
            store,
            "parent",
            mid = "m2",
            createdAt = Instant.fromEpochMilliseconds(baseTime.toEpochMilliseconds() + 1),
        )

        val result = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = null),
            ctx = context(parentId),
        )

        assertEquals(2, result.data.forkCopiedMessageCount, "full history copied")
        assertNull(result.data.forkAnchorMessageId, "no anchor in result")
    }

    @Test fun blankAnchorIsTreatedAsNullFullHistoryCopied() = runTest {
        // Pin: per `input.anchorMessageId?.takeIf { it.isNotBlank() }`,
        // an empty/whitespace anchor resolves to null
        // (full history). Drift to "blank treated as
        // literal id" would fail "fork everything" when
        // the agent passes "".
        val store = newStore()
        val parentId = seedSession(store, "parent")
        seedMessage(store, "parent", mid = "m1")

        val resultEmpty = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = ""),
            ctx = context(parentId),
        )
        assertEquals(1, resultEmpty.data.forkCopiedMessageCount, "empty anchor is no-anchor")
        assertNull(resultEmpty.data.forkAnchorMessageId)

        val resultBlank = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = "   "),
            ctx = context(parentId),
        )
        assertEquals(1, resultBlank.data.forkCopiedMessageCount, "whitespace anchor is no-anchor")
        assertNull(resultBlank.data.forkAnchorMessageId)
    }

    @Test fun nonBlankAnchorPrunesEverythingAfterAnchor() = runTest {
        // Marquee anchor pin: when anchorId is set, only
        // messages at-or-before the anchor are copied.
        val store = newStore()
        val parentId = seedSession(store, "parent")
        // 3 messages with monotonically increasing timestamps.
        seedMessage(store, "parent", mid = "m1", createdAt = baseTime)
        seedMessage(
            store,
            "parent",
            mid = "m2",
            createdAt = Instant.fromEpochMilliseconds(baseTime.toEpochMilliseconds() + 1),
        )
        seedMessage(
            store,
            "parent",
            mid = "m3",
            createdAt = Instant.fromEpochMilliseconds(baseTime.toEpochMilliseconds() + 2),
        )

        // Fork at m2 — should copy m1 + m2, drop m3.
        val result = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = "m2"),
            ctx = context(parentId),
        )

        assertEquals(2, result.data.forkCopiedMessageCount, "anchor includes itself")
        assertEquals("m2", result.data.forkAnchorMessageId, "anchor echoed in result")
    }

    // ── newTitle semantics ───────────────────────────────────

    @Test fun nullNewTitleUsesStoreDefaultTitle() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent", title = "Original")

        val result = executeSessionFork(
            sessions = store,
            input = input(newTitle = null),
            ctx = context(parentId),
        )
        // Store default is "<parent> (fork)".
        assertEquals(
            "Original (fork)",
            result.data.newTitle,
            "default title is '<parent> (fork)'; got: ${result.data.newTitle}",
        )
    }

    @Test fun blankNewTitleIsTreatedAsNullDefaultUsed() = runTest {
        // Pin: per `input.newTitle?.takeIf { it.isNotBlank() }`,
        // empty/whitespace title resolves to null → store
        // default. Drift to "use empty string verbatim"
        // would silently create sessions with whitespace
        // titles.
        val store = newStore()
        val parentId = seedSession(store, "parent", title = "Original")

        val resultEmpty = executeSessionFork(
            sessions = store,
            input = input(newTitle = ""),
            ctx = context(parentId),
        )
        assertEquals("Original (fork)", resultEmpty.data.newTitle)

        val resultBlank = executeSessionFork(
            sessions = store,
            input = input(newTitle = "   "),
            ctx = context(parentId),
        )
        assertEquals("Original (fork)", resultBlank.data.newTitle)
    }

    @Test fun nonBlankNewTitleIsHonored() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent", title = "Original")

        val result = executeSessionFork(
            sessions = store,
            input = input(newTitle = "Custom Branch Title"),
            ctx = context(parentId),
        )
        assertEquals("Custom Branch Title", result.data.newTitle)
    }

    // ── Output shape ─────────────────────────────────────────

    @Test fun outputCarriesParentTitleAndNewTitleSeparately() = runTest {
        // Pin: data.title is parent's title; data.newTitle
        // is fork's title. Drift to "echo same title in
        // both" would lose the discriminator.
        val store = newStore()
        val parentId = seedSession(store, "parent", title = "Parent Title")

        val result = executeSessionFork(
            sessions = store,
            input = input(newTitle = "Fork Title"),
            ctx = context(parentId),
        )
        assertEquals("Parent Title", result.data.title, "data.title = parent's title")
        assertEquals("Fork Title", result.data.newTitle, "data.newTitle = fork's title")
    }

    @Test fun outputForkedSessionIdReferencesNewlyCreatedSession() = runTest {
        // Pin: forkedSessionId is the NEW session's id
        // (NOT the parent's id). Drift to "echo parent
        // id" would silently mis-bind the agent's
        // following turns to the wrong session.
        val store = newStore()
        val parentId = seedSession(store, "parent")

        val result = executeSessionFork(
            sessions = store,
            input = input(),
            ctx = context(parentId),
        )

        assertTrue(result.data.forkedSessionId != null, "forkedSessionId populated")
        assertTrue(
            result.data.forkedSessionId != parentId.value,
            "forkedSessionId differs from parent",
        )
        // The new session exists in the store.
        assertTrue(
            store.getSession(SessionId(result.data.forkedSessionId!!)) != null,
            "forkedSessionId resolves to a real session",
        )
    }

    @Test fun outputDataSessionIdEchoesParentSessionId() = runTest {
        // Pin: data.sessionId is the PARENT's session id
        // (NOT the fork's). The action targeted the parent;
        // the fork is the new artifact.
        val store = newStore()
        val parentId = seedSession(store, "parent")

        val result = executeSessionFork(
            sessions = store,
            input = input(),
            ctx = context(parentId),
        )
        assertEquals(parentId.value, result.data.sessionId)
        assertEquals("fork", result.data.action)
    }

    // ── Summary format ──────────────────────────────────────

    @Test fun summaryCitesBothSessionsAndCopyCount() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent", title = "Parent")
        seedMessage(store, "parent", mid = "m1")

        val result = executeSessionFork(
            sessions = store,
            input = input(newTitle = "Branch"),
            ctx = context(parentId),
        )

        // outputForLlm cites parent + new session + count.
        assertTrue("Forked session" in result.outputForLlm)
        assertTrue("'Parent'" in result.outputForLlm, "parent title cited")
        assertTrue("'Branch'" in result.outputForLlm, "fork title cited")
        assertTrue(
            "1 message(s)" in result.outputForLlm,
            "copy count cited; got: ${result.outputForLlm}",
        )
    }

    @Test fun summaryOmitsAnchorNoteWhenAnchorAbsent() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent")
        seedMessage(store, "parent", mid = "m1")

        val result = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = null),
            ctx = context(parentId),
        )
        assertTrue(
            "at anchor" !in result.outputForLlm,
            "no anchor → no anchor note; got: ${result.outputForLlm}",
        )
    }

    @Test fun summaryAppendsAnchorNoteWhenAnchorPresent() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "parent")
        seedMessage(store, "parent", mid = "m1")

        val result = executeSessionFork(
            sessions = store,
            input = input(anchorMessageId = "m1"),
            ctx = context(parentId),
        )
        assertTrue(
            "at anchor m1" in result.outputForLlm,
            "anchor note cited; got: ${result.outputForLlm}",
        )
    }

    // ── ctx.resolveSessionId fallback ────────────────────────

    @Test fun nullInputSessionIdFallsBackToContextSessionId() = runTest {
        val store = newStore()
        val ctxSid = seedSession(store, "ctx-session")
        val result = executeSessionFork(
            sessions = store,
            input = input(sessionId = null),
            ctx = context(ctxSid),
        )
        assertEquals(ctxSid.value, result.data.sessionId, "ctx.sessionId fallback")
    }

    @Test fun explicitInputSessionIdHonoredOverContext() = runTest {
        val store = newStore()
        seedSession(store, "explicit")
        val ctxSid = seedSession(store, "ctx-session")

        val result = executeSessionFork(
            sessions = store,
            input = input(sessionId = "explicit"),
            ctx = context(ctxSid),
        )
        assertEquals("explicit", result.data.sessionId, "explicit input wins")
    }

    // ── ToolResult.title ─────────────────────────────────────

    @Test fun toolResultTitleCitesParentSessionId() = runTest {
        val store = newStore()
        val parentId = seedSession(store, "p1")

        val result = executeSessionFork(
            sessions = store,
            input = input(),
            ctx = context(parentId),
        )
        assertTrue("p1" in result.title!!)
        assertTrue("fork session" in result.title!!)
    }
}
