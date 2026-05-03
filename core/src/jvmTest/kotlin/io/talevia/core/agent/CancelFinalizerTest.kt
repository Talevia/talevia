package io.talevia.core.agent

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
import io.talevia.core.session.ToolState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [finalizeCancelled] — the cancel-path side-effect
 * bundle that stamps `FinishReason.CANCELLED` on the in-flight assistant
 * message and converts every `Pending` / `Running` Tool part on that
 * message to [ToolState.Cancelled]. Cycle 97 audit: 75-LOC internal
 * function with **zero** direct test references; only exercised
 * transitively via cancellation paths through `Agent.run`.
 *
 * The fan-out is the contract worth pinning: a regression that drops the
 * tool-part sweep would leave Tool parts in `Pending` / `Running` forever
 * in the session log, misleading any post-mortem query about what
 * happened. A regression that overwrites Pending/Running indiscriminately
 * (or also stamps Completed/Failed) would silently corrupt a session that
 * legitimately had completed tools before the cancel landed.
 *
 * The kdoc explicitly commits to a few subtle behaviours that this test
 * locks down:
 * - null assistantId → silent no-op (no exception)
 * - already-finished message → race-protection no-op
 * - non-assistant message with same id → no-op (defensive `as?`)
 * - tool parts on OTHER messages of the same session must not be touched
 * - `Running.input` must round-trip into `Cancelled.input`; `Pending`
 *   parts have no input yet, so `Cancelled.input` is null
 */
class CancelFinalizerTest {

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private val sessionId = SessionId("s-1")
    private val now: Instant = Clock.System.now()

    private suspend fun primeSession(store: SqlDelightSessionStore) {
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("proj"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun appendAssistant(
        store: SqlDelightSessionStore,
        id: String,
        finish: FinishReason? = null,
    ): MessageId {
        // Prime the user/parent message so the FK chain holds — we need
        // any Message to act as parent.
        val parentId = MessageId("u-$id")
        store.appendMessage(
            Message.User(
                id = parentId,
                sessionId = sessionId,
                createdAt = now,
                agent = "test",
                model = ModelRef("fake", "test"),
            ),
        )
        val mid = MessageId(id)
        store.appendMessage(
            Message.Assistant(
                id = mid,
                sessionId = sessionId,
                createdAt = now,
                parentId = parentId,
                model = ModelRef("fake", "test"),
                finish = finish,
            ),
        )
        return mid
    }

    private suspend fun appendUser(
        store: SqlDelightSessionStore,
        id: String,
    ): MessageId {
        val mid = MessageId(id)
        store.appendMessage(
            Message.User(
                id = mid,
                sessionId = sessionId,
                createdAt = now,
                agent = "test",
                model = ModelRef("fake", "test"),
            ),
        )
        return mid
    }

    private suspend fun toolPart(
        store: SqlDelightSessionStore,
        partId: String,
        messageId: MessageId,
        state: ToolState,
    ): Part.Tool {
        val p = Part.Tool(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sessionId,
            createdAt = now,
            callId = CallId("call-$partId"),
            toolId = "echo",
            state = state,
        )
        store.upsertPart(p)
        return p
    }

    @Test fun nullAssistantIdIsNoOp() = runTest {
        val store = newStore()
        primeSession(store)
        // Pin: passing null returns silently — no exception, no DB change.
        finalizeCancelled(store, assistantId = null, reason = "user cancel")
        // Trivial assertion — the test passes if no exception escaped.
        assertTrue(true)
    }

    @Test fun unknownMessageIdIsNoOp() = runTest {
        val store = newStore()
        primeSession(store)
        // Message never appended — `getMessage` returns null.
        finalizeCancelled(store, MessageId("ghost"), "x")
        // No throw; no message means nothing to update.
        assertNull(store.getMessage(MessageId("ghost")))
    }

    @Test fun nonAssistantMessageIsNoOp() = runTest {
        val store = newStore()
        primeSession(store)
        val userId = appendUser(store, "u-1")
        // The kdoc commits to the defensive `as?` cast — passing a User
        // message id must NOT throw a ClassCastException.
        finalizeCancelled(store, userId, "x")
        // User message untouched (User has no `finish` field, just
        // assert the message is still there + still a User).
        assertTrue(store.getMessage(userId) is Message.User)
    }

    @Test fun alreadyFinishedMessageIsNotOverwritten() = runTest {
        val store = newStore()
        primeSession(store)
        // Race protection: streamTurn already wrote `END_TURN` before
        // the cancel landed — finalizeCancelled MUST keep END_TURN.
        val asstId = appendAssistant(store, "a-1", finish = FinishReason.END_TURN)
        finalizeCancelled(store, asstId, "user cancel")
        val msg = store.getMessage(asstId) as Message.Assistant
        assertEquals(
            FinishReason.END_TURN,
            msg.finish,
            "race-protection: must not overwrite already-finished message",
        )
        assertNull(msg.error, "error must stay null on already-finished message")
    }

    @Test fun stampsCancelledFinishOnAssistant() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        finalizeCancelled(store, asstId, "user cancel")
        val msg = store.getMessage(asstId) as Message.Assistant
        assertEquals(FinishReason.CANCELLED, msg.finish)
        assertEquals("user cancel", msg.error)
    }

    @Test fun nullReasonDefaultsToCancelledLiteral() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        // Pin the kdoc default: null reason → "cancelled" string.
        // `Message.error` field carries the same default.
        finalizeCancelled(store, asstId, reason = null)
        val msg = store.getMessage(asstId) as Message.Assistant
        assertEquals(FinishReason.CANCELLED, msg.finish)
        assertEquals("cancelled", msg.error)
    }

    @Test fun runningToolPartIsCancelledWithInputPreserved() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        val input = buildJsonObject { put("text", "ping") }
        toolPart(store, "p-running", asstId, ToolState.Running(input))
        finalizeCancelled(store, asstId, "boom")
        val updated = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-running") }
        val state = updated.state
        assertTrue(state is ToolState.Cancelled, "Running → Cancelled: ${updated.state}")
        // Pin: input round-trips. Without this, the cancel would lose
        // the LLM's last-known arguments — making post-mortem debugging
        // of "what was the tool about to do" impossible.
        assertEquals(input, (state as ToolState.Cancelled).input)
        assertEquals("boom", state.message)
    }

    @Test fun pendingToolPartIsCancelledWithNullInput() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        toolPart(store, "p-pending", asstId, ToolState.Pending)
        finalizeCancelled(store, asstId, "boom")
        val updated = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-pending") }
        val state = updated.state
        assertTrue(state is ToolState.Cancelled, "Pending → Cancelled")
        // Pending parts haven't streamed any input yet — `Cancelled.input`
        // is null per the kdoc.
        assertNull((state as ToolState.Cancelled).input)
        assertEquals("boom", state.message)
    }

    @Test fun completedToolPartIsUntouched() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        val completed = ToolState.Completed(
            input = buildJsonObject { put("text", "ping") },
            outputForLlm = "pong",
            data = JsonPrimitive("pong"),
        )
        toolPart(store, "p-done", asstId, completed)
        finalizeCancelled(store, asstId, "boom")
        val updated = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-done") }
        // Pin: completed tool calls predate the cancel — they must NOT
        // be re-stamped (would corrupt session history).
        assertTrue(updated.state is ToolState.Completed, "Completed must stay Completed")
        assertEquals("pong", (updated.state as ToolState.Completed).outputForLlm)
    }

    @Test fun failedToolPartIsUntouched() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        val failed = ToolState.Failed(
            input = buildJsonObject { put("x", 1) },
            message = "boom",
        )
        toolPart(store, "p-fail", asstId, failed)
        finalizeCancelled(store, asstId, "user cancel")
        val updated = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-fail") }
        assertTrue(updated.state is ToolState.Failed, "Failed must stay Failed")
        // Pin: original failure message preserved (NOT overwritten with
        // "user cancel"). Distinguishing a legit tool error from a cancel
        // is the whole point of the Cancelled variant — keep them separate.
        assertEquals("boom", (updated.state as ToolState.Failed).message)
    }

    @Test fun alreadyCancelledToolPartIsUntouched() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        val pre = ToolState.Cancelled(
            input = JsonPrimitive("orig-input"),
            message = "first-cancel",
        )
        toolPart(store, "p-cxl", asstId, pre)
        // Idempotency: calling finalizeCancelled twice / on a part that
        // raced into Cancelled state must be a no-op for that part.
        finalizeCancelled(store, asstId, "second-cancel")
        val updated = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-cxl") }
        val state = updated.state
        assertTrue(state is ToolState.Cancelled)
        // Original message + input preserved.
        assertEquals("first-cancel", (state as ToolState.Cancelled).message)
        assertEquals(JsonPrimitive("orig-input"), state.input)
    }

    @Test fun otherMessagesToolPartsUntouched() = runTest {
        val store = newStore()
        primeSession(store)
        val cancelMsg = appendAssistant(store, "a-cancel")
        val otherMsg = appendAssistant(store, "a-other", finish = FinishReason.END_TURN)
        // Tool part on the cancelled message — should flip.
        toolPart(store, "p-self", cancelMsg, ToolState.Running(JsonPrimitive("x")))
        // Tool part on a different (already-finished) message — must be
        // left alone. This is the test that catches a regression
        // accidentally widening the scope to all session parts.
        toolPart(store, "p-other", otherMsg, ToolState.Running(JsonPrimitive("y")))

        finalizeCancelled(store, cancelMsg, "boom")

        val parts = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>()
        val self = parts.single { it.id == PartId("p-self") }
        val other = parts.single { it.id == PartId("p-other") }
        assertTrue(self.state is ToolState.Cancelled, "self message tool part must be cancelled")
        assertTrue(
            other.state is ToolState.Running,
            "other message tool part must remain Running — got: ${other.state}",
        )
    }

    @Test fun cancelMessageIsCopiedToBothFinishAndToolParts() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        toolPart(store, "p1", asstId, ToolState.Pending)
        toolPart(store, "p2", asstId, ToolState.Running(JsonPrimitive("x")))

        val reason = "user pressed Ctrl-C"
        finalizeCancelled(store, asstId, reason)

        val msg = store.getMessage(asstId) as Message.Assistant
        assertEquals(reason, msg.error)
        val tools = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>()
        assertEquals(
            2,
            tools.count { (it.state as? ToolState.Cancelled)?.message == reason },
            "both tool parts must carry the same cancel reason",
        )
    }

    @Test fun nonToolPartsOnTheSameMessageAreUntouched() = runTest {
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        toolPart(store, "p-tool", asstId, ToolState.Pending)
        val text = Part.Text(
            id = PartId("p-text"),
            messageId = asstId,
            sessionId = sessionId,
            createdAt = now,
            text = "partial reply…",
        )
        store.upsertPart(text)

        finalizeCancelled(store, asstId, "boom")

        // Text part untouched (still there with original content).
        val texts = store.listSessionParts(sessionId).filterIsInstance<Part.Text>()
        val partial = texts.single { it.id == PartId("p-text") }
        assertEquals("partial reply…", partial.text, "non-Tool parts must not be rewritten")
        // Tool part flipped.
        val tool = store.listSessionParts(sessionId)
            .filterIsInstance<Part.Tool>()
            .single { it.id == PartId("p-tool") }
        assertTrue(tool.state is ToolState.Cancelled)
    }

    @Test fun handlesAssistantWithNoToolParts() = runTest {
        // Pin: no tool parts → just stamps the message. No iteration
        // crash; no spurious updates.
        val store = newStore()
        primeSession(store)
        val asstId = appendAssistant(store, "a-1")
        finalizeCancelled(store, asstId, "boom")
        val msg = store.getMessage(asstId) as Message.Assistant
        assertEquals(FinishReason.CANCELLED, msg.finish)
        // No Tool parts emitted.
        val tools = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>()
        assertTrue(tools.isEmpty())
        // Sanity: the message is still queryable.
        assertNotNull(store.getMessage(asstId))
    }
}
