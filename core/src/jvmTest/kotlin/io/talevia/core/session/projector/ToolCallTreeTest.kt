package io.talevia.core.session.projector

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [ToolCallTreeProjector] —
 * `core/session/projector/ToolCallTree.kt`. The chat-UI
 * projector that walks a session's messages-with-parts into
 * a turn-oriented tree (user prompt → assistant turn → tool
 * calls). Cycle 153 audit: 110 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Only `Message.Assistant` messages become turn
 *    anchors.** User messages are NOT turns themselves —
 *    they appear as the `userMessageId` field of a paired
 *    Assistant turn. Drift to including User messages as
 *    turn nodes would double-render every prompt in the
 *    chat-UI scroll.
 *
 * 2. **Tool calls within a turn sort ascending by
 *    `createdAt`.** UI scroll within a turn matches dispatch
 *    order — first-fired tool tops the children, last-fired
 *    bottoms. Drift to descending or insertion order would
 *    confuse "what happened first?" reading of a multi-tool
 *    turn.
 *
 * 3. **`ToolState` → label mapping covers all 5 variants.**
 *    `Pending` → "pending", `Running` → "running",
 *    `Completed` → "completed", `Failed` → "error" (NB:
 *    NOT "failed" — UI semantic is "error"), `Cancelled` →
 *    "cancelled". A regression dropping the Failed→"error"
 *    mapping would silently break every chat-UI badge that
 *    keys on the string.
 */
class ToolCallTreeTest {

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

    private suspend fun appendUser(
        store: SqlDelightSessionStore,
        sid: String,
        uid: String,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId(uid),
                sessionId = SessionId(sid),
                createdAt = now,
                agent = "test",
                model = ModelRef("anthropic", "claude-3-5"),
            ),
        )
    }

    private suspend fun appendAssistant(
        store: SqlDelightSessionStore,
        sid: String,
        aid: String,
        parentId: String,
        finish: FinishReason? = null,
        tokensInput: Long = 0,
        tokensOutput: Long = 0,
    ) {
        store.appendMessage(
            Message.Assistant(
                id = MessageId(aid),
                sessionId = SessionId(sid),
                createdAt = now,
                parentId = MessageId(parentId),
                model = ModelRef("anthropic", "claude-3-5"),
                finish = finish,
                tokens = TokenUsage(input = tokensInput, output = tokensOutput),
            ),
        )
    }

    private suspend fun appendToolPart(
        store: SqlDelightSessionStore,
        sid: String,
        aid: String,
        partId: String,
        callId: String,
        toolId: String,
        state: ToolState,
        createdAtEpochMs: Long = 0L,
        title: String? = null,
    ) {
        store.upsertPart(
            Part.Tool(
                id = PartId(partId),
                messageId = MessageId(aid),
                sessionId = SessionId(sid),
                createdAt = if (createdAtEpochMs == 0L) now else Instant.fromEpochMilliseconds(createdAtEpochMs),
                callId = CallId(callId),
                toolId = toolId,
                state = state,
                title = title,
            ),
        )
    }

    // ── only-Assistant turn anchors ─────────────────────────────

    @Test fun userOnlySessionProducesEmptyTurns() = runTest {
        // Pin: a session with only User messages produces 0
        // turns. The projector's `mapNotNull` filters to
        // Assistant only.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u1")
        appendUser(store, "s1", "u2")

        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("s1", tree.sessionId)
        assertEquals(0, tree.turns.size, "user-only session has 0 assistant turns")
    }

    @Test fun mixedSessionPicksUpAssistantTurnsOnly() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u1")
        appendAssistant(store, "s1", "a1", parentId = "u1", finish = FinishReason.STOP)
        appendUser(store, "s1", "u2")
        appendAssistant(store, "s1", "a2", parentId = "u2", finish = FinishReason.END_TURN)

        val tree = ToolCallTreeProjector(store).project(sid)
        // Pin: 2 Assistant messages → 2 turns (both User
        // messages are referenced as parentId, NOT as turns).
        assertEquals(2, tree.turns.size)
        assertEquals(setOf("a1", "a2"), tree.turns.map { it.assistantMessageId }.toSet())
    }

    @Test fun assistantTurnReferencesUserParentInUserMessageId() {
        // Pin: TurnNode.userMessageId = assistant.parentId.value.
        // Drift would break "show the user's prompt" rendering.
        val store = newStore()
        runTest {
            val sid = seedSession(store, "s1")
            appendUser(store, "s1", "u-question")
            appendAssistant(store, "s1", "a-answer", parentId = "u-question")
            val tree = ToolCallTreeProjector(store).project(sid)
            val turn = tree.turns.single()
            assertEquals("u-question", turn.userMessageId)
            assertEquals("a-answer", turn.assistantMessageId)
        }
    }

    // ── tool-call sort within turn ──────────────────────────────

    @Test fun toolCallsWithinTurnSortAscendingByCreatedAt() = runTest {
        // Marquee sort pin: dispatch order matters for the UI
        // scroll within a turn. Plant 3 tool parts with
        // out-of-order createdAt and assert ascending output.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u", finish = FinishReason.TOOL_CALLS)
        // Insert in shuffled order: middle, last, first.
        appendToolPart(store, "s1", "a", "p-mid", "c-mid", "tool-b", ToolState.Pending, createdAtEpochMs = 200L)
        appendToolPart(store, "s1", "a", "p-last", "c-last", "tool-c", ToolState.Pending, createdAtEpochMs = 300L)
        appendToolPart(store, "s1", "a", "p-first", "c-first", "tool-a", ToolState.Pending, createdAtEpochMs = 100L)

        val tree = ToolCallTreeProjector(store).project(sid)
        val calls = tree.turns.single().toolCalls
        assertEquals(3, calls.size)
        // Pin: ascending order.
        assertEquals(
            listOf("tool-a", "tool-b", "tool-c"),
            calls.map { it.toolId },
            "ascending by createdAt; got: ${calls.map { it.toolId to it.createdAtEpochMs }}",
        )
        assertEquals(listOf(100L, 200L, 300L), calls.map { it.createdAtEpochMs })
    }

    // ── ToolState → label mapping ───────────────────────────────

    @Test fun pendingStateMapsToPendingLabel() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(store, "s1", "a", "p", "c", "t", ToolState.Pending)
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("pending", tree.turns.single().toolCalls.single().state)
    }

    @Test fun runningStateMapsToRunningLabel() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(
            store,
            "s1",
            "a",
            "p",
            "c",
            "t",
            ToolState.Running(input = JsonObject(emptyMap())),
        )
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("running", tree.turns.single().toolCalls.single().state)
    }

    @Test fun completedStateMapsToCompletedLabel() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(
            store,
            "s1",
            "a",
            "p",
            "c",
            "t",
            ToolState.Completed(input = JsonNull, outputForLlm = "ok", data = JsonObject(emptyMap())),
        )
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("completed", tree.turns.single().toolCalls.single().state)
    }

    @Test fun failedStateMapsToErrorLabelNotFailed() = runTest {
        // Marquee label-rename pin: ToolState.Failed → "error"
        // (NOT "failed"). The UI semantic is "error" — drift
        // to "failed" would silently break every CSS class /
        // template branch that keys on the string "error".
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(
            store,
            "s1",
            "a",
            "p",
            "c",
            "t",
            ToolState.Failed(input = null, message = "boom"),
        )
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals(
            "error",
            tree.turns.single().toolCalls.single().state,
            "Failed → 'error' (NOT 'failed')",
        )
    }

    @Test fun cancelledStateMapsToCancelledLabel() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(
            store,
            "s1",
            "a",
            "p",
            "c",
            "t",
            ToolState.Cancelled(input = null, message = "user-abort"),
        )
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("cancelled", tree.turns.single().toolCalls.single().state)
    }

    // ── turn-level field mapping ────────────────────────────────

    @Test fun finishReasonLowercasedInTurnNode() = runTest {
        // Pin: assistant.finish?.name?.lowercase(). Enum
        // names are UPPER_SNAKE — UI expects lowercase string.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u", finish = FinishReason.TOOL_CALLS)
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals("tool_calls", tree.turns.single().finish)
    }

    @Test fun unfinishedTurnHasNullFinish() = runTest {
        // Pin: in-flight turn has finish=null. UI uses this to
        // show the spinner. Drift to "in_flight" or "" would
        // confuse with a real terminal state.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u", finish = null)
        val tree = ToolCallTreeProjector(store).project(sid)
        assertNull(tree.turns.single().finish)
    }

    @Test fun tokensInputAndOutputThreadedThrough() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(
            store,
            "s1",
            "a",
            parentId = "u",
            tokensInput = 1234L,
            tokensOutput = 567L,
            finish = FinishReason.STOP,
        )
        val turn = ToolCallTreeProjector(store).project(sid).turns.single()
        assertEquals(1234L, turn.tokensInput)
        assertEquals(567L, turn.tokensOutput)
    }

    // ── tool-call node fields ───────────────────────────────────

    @Test fun toolCallNodePreservesAllFields() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        appendToolPart(
            store,
            "s1",
            "a",
            "p-1",
            "c-7",
            "generate_image",
            ToolState.Completed(input = JsonNull, outputForLlm = "ok", data = JsonObject(emptyMap())),
            createdAtEpochMs = 1_000L,
            title = "hero shot",
        )
        val node = ToolCallTreeProjector(store).project(sid).turns.single().toolCalls.single()
        assertEquals("p-1", node.partId)
        assertEquals("c-7", node.callId)
        assertEquals("generate_image", node.toolId)
        assertEquals("completed", node.state)
        assertEquals("hero shot", node.title)
        assertEquals(1_000L, node.createdAtEpochMs)
    }

    @Test fun nonToolPartsAreIgnoredAsChildren() = runTest {
        // Pin: filterIsInstance<Part.Tool>() keeps only Tool
        // parts. Text / Reasoning / Compaction parts on an
        // Assistant message should NOT appear in toolCalls.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u")
        // Plant a Text part on the assistant.
        store.upsertPart(
            Part.Text(
                id = PartId("p-text"),
                messageId = MessageId("a"),
                sessionId = SessionId("s1"),
                createdAt = now,
                text = "thinking out loud",
            ),
        )
        // Plant a Tool part too.
        appendToolPart(store, "s1", "a", "p-tool", "c", "t", ToolState.Pending)

        val turn = ToolCallTreeProjector(store).project(sid).turns.single()
        assertEquals(1, turn.toolCalls.size, "only Tool parts surface as toolCalls")
        assertEquals("p-tool", turn.toolCalls.single().partId)
    }

    @Test fun emptySessionProducesEmptyTurns() = runTest {
        val store = newStore()
        val sid = seedSession(store, "s1")
        // No messages.
        val tree = ToolCallTreeProjector(store).project(sid)
        assertEquals(0, tree.turns.size)
        assertEquals("s1", tree.sessionId, "sessionId echoed even when empty")
    }

    @Test fun assistantWithNoToolCallsHasEmptyChildrenList() = runTest {
        // Pin: `text-only` assistant turns produce a TurnNode
        // with empty toolCalls. Drift to dropping the turn
        // entirely would hide every "I'll just reply with
        // text" turn from the chat UI.
        val store = newStore()
        val sid = seedSession(store, "s1")
        appendUser(store, "s1", "u")
        appendAssistant(store, "s1", "a", parentId = "u", finish = FinishReason.STOP)

        val turns = ToolCallTreeProjector(store).project(sid).turns
        assertEquals(1, turns.size, "text-only turn still surfaces")
        assertTrue(turns.single().toolCalls.isEmpty())
    }
}
