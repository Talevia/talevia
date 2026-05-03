package io.talevia.core.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [SessionStore.currentTodos] —
 * `core/session/Todos.kt`. The "what's the agent's current
 * plan?" accessor that walks a session's parts, picks the
 * latest `Part.Todos`, and returns its todo list. Cycle 154
 * audit: 20 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **No `Part.Todos` parts → empty list, NOT throw.** A
 *    session whose agent never used `todowrite` legitimately
 *    has no plan; the accessor returns empty rather than
 *    erroring so callers (UI panel, agent re-entry) can
 *    treat absence as "no plan yet" without a try/catch.
 *
 * 2. **Multiple `Part.Todos` parts → most-recent (`maxByOrNull
 *    { createdAt }`) wins.** Per kdoc: "the most recent
 *    `Part.Todos` in a session is the current state." Drift
 *    to first-by-insertion-order or earliest-by-createdAt
 *    would surface stale plans while the agent's current
 *    state stays hidden.
 *
 * 3. **`includeCompacted = true` so even compacted Todos
 *    parts surface.** The agent may have compacted out a
 *    stale plan but the LATEST plan part still lives in
 *    history. Drift to `false` would silently lose the
 *    plan after the first compaction pass.
 */
class TodosTest {

    private val now: Instant = Clock.System.now()

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            TaleviaDb.Schema.create(it)
        }
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(store: SqlDelightSessionStore, sid: String): SessionId {
        val sessionId = SessionId(sid)
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sessionId
    }

    private suspend fun seedAssistantMessage(
        store: SqlDelightSessionStore,
        sid: String,
        aid: String,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId("u-anchor"),
                sessionId = SessionId(sid),
                createdAt = now,
                agent = "test",
                model = ModelRef("anthropic", "claude-3-5"),
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = MessageId(aid),
                sessionId = SessionId(sid),
                createdAt = now,
                parentId = MessageId("u-anchor"),
                model = ModelRef("anthropic", "claude-3-5"),
            ),
        )
    }

    private suspend fun seedTodos(
        store: SqlDelightSessionStore,
        sid: String,
        aid: String,
        partId: String,
        createdAtEpochMs: Long,
        todos: List<TodoInfo>,
        compactedAt: Instant? = null,
    ) {
        store.upsertPart(
            Part.Todos(
                id = PartId(partId),
                messageId = MessageId(aid),
                sessionId = SessionId(sid),
                createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
                compactedAt = compactedAt,
                todos = todos,
            ),
        )
    }

    // ── empty session → empty list ──────────────────────────────

    @Test fun sessionWithNoTodosPartsReturnsEmptyList() = runTest {
        // Pin: a session where the agent never used
        // todowrite has no Part.Todos. Returns empty list,
        // NOT throw. UI panel + agent re-entry both treat
        // empty as "no plan yet."
        val store = newStore()
        val sid = seedSession(store, "s1")
        // No messages, no parts.
        assertEquals(emptyList(), store.currentTodos(sid))
    }

    @Test fun sessionWithMessagesButNoTodosPartsReturnsEmptyList() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        // Plant a Text part instead of Todos.
        store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("a1"),
                sessionId = sid,
                createdAt = now,
                text = "thinking out loud",
            ),
        )
        assertEquals(emptyList(), store.currentTodos(sid))
    }

    // ── most-recent wins ────────────────────────────────────────

    @Test fun multipleTodosPartsLatestByCreatedAtWins() = runTest {
        // The marquee most-recent pin: agent updated its plan
        // 3 times. The LATEST update wins, NOT the first or
        // the middle. Drift to first / earliest would surface
        // stale plans.
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        // Plant 3 Todos parts with shuffled epoch order.
        seedTodos(
            store, "s1", "a1", "p-mid", createdAtEpochMs = 200L,
            todos = listOf(TodoInfo("middle plan")),
        )
        seedTodos(
            store, "s1", "a1", "p-first", createdAtEpochMs = 100L,
            todos = listOf(TodoInfo("first plan")),
        )
        seedTodos(
            store, "s1", "a1", "p-latest", createdAtEpochMs = 300L,
            todos = listOf(TodoInfo("LATEST plan", status = TodoStatus.IN_PROGRESS)),
        )

        val current = store.currentTodos(sid)
        assertEquals(1, current.size, "only the latest survives — NOT all 3")
        assertEquals("LATEST plan", current.single().content)
        assertEquals(TodoStatus.IN_PROGRESS, current.single().status)
    }

    @Test fun singleTodosPartReturnsItsTodos() = runTest {
        // Pin: trivial happy path — one Todos part, returns
        // its list. Confirms the file's primary use case.
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        val plan = listOf(
            TodoInfo("step 1", status = TodoStatus.COMPLETED, priority = TodoPriority.HIGH),
            TodoInfo("step 2", status = TodoStatus.IN_PROGRESS),
            TodoInfo("step 3", status = TodoStatus.PENDING, priority = TodoPriority.LOW),
        )
        seedTodos(store, "s1", "a1", "p-only", createdAtEpochMs = 1L, todos = plan)

        val current = store.currentTodos(sid)
        assertEquals(plan, current, "all 3 todos roundtrip with status + priority intact")
    }

    // ── compacted Todos still surface ──────────────────────────

    @Test fun compactedTodosStillSurfaceAsCurrentPlan() = runTest {
        // The marquee includeCompacted=true pin: per kdoc-
        // adjacent reasoning, a compacted Todos part is
        // semantically still the agent's plan-of-record.
        // listSessionParts(includeCompacted = true) lets the
        // compacted plan surface even after the compactor
        // marks it. Drift to `includeCompacted = false` would
        // silently zero the plan after compaction.
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        // Plant a compacted Todos part — should still be
        // reachable as the latest plan since it's the only one.
        seedTodos(
            store, "s1", "a1", "p-compacted", createdAtEpochMs = 1L,
            todos = listOf(TodoInfo("survived compaction")),
            compactedAt = now,
        )
        val current = store.currentTodos(sid)
        assertEquals(1, current.size, "compacted Todos still surface")
        assertEquals("survived compaction", current.single().content)
    }

    @Test fun mixedCompactedAndUncompactedLatestStillWins() = runTest {
        // Pin: latest wins regardless of compaction flag —
        // the kdoc says "most recent." A compacted part with
        // a LATER createdAt should still beat an uncompacted
        // earlier one. Drift to "skip compacted" would
        // silently surface the stale uncompacted plan.
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        // Earlier uncompacted.
        seedTodos(
            store, "s1", "a1", "p-old", createdAtEpochMs = 100L,
            todos = listOf(TodoInfo("old uncompacted")),
            compactedAt = null,
        )
        // Later compacted.
        seedTodos(
            store, "s1", "a1", "p-new-compacted", createdAtEpochMs = 200L,
            todos = listOf(TodoInfo("new compacted")),
            compactedAt = now,
        )
        val current = store.currentTodos(sid)
        assertEquals(
            "new compacted",
            current.single().content,
            "latest wins even when compacted",
        )
    }

    // ── empty Todos list ───────────────────────────────────────

    @Test fun latestTodosPartWithEmptyListReturnsEmptyListNotEarlierPlan() = runTest {
        // Pin: an explicit "todos = []" most-recent overrides
        // any earlier plan. The agent emitting an empty
        // todowrite is its "I'm done planning" signal — drift
        // that ignored empty in favour of an earlier non-
        // empty would surface stale to-do items the agent
        // already cleared.
        val store = newStore()
        val sid = seedSession(store, "s1")
        seedAssistantMessage(store, "s1", "a1")
        seedTodos(
            store, "s1", "a1", "p-old-with-items", createdAtEpochMs = 100L,
            todos = listOf(TodoInfo("not done"), TodoInfo("also not done")),
        )
        seedTodos(
            store, "s1", "a1", "p-new-empty", createdAtEpochMs = 200L,
            todos = emptyList(),
        )
        assertEquals(
            emptyList(),
            store.currentTodos(sid),
            "explicit-empty latest plan overrides earlier non-empty",
        )
    }
}
