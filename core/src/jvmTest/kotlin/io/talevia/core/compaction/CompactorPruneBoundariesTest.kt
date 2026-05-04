package io.talevia.core.compaction

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.agent.FakeProvider
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Timeline
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.ToolState
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boundary-edge pin for [Compactor.prune]. The sibling [CompactorTest]
 * covers happy-path drop logic (biggest-first ordering, default-bound
 * thresholds, estimatedTokens override) but does NOT pin the
 * **short-circuit edges** where a refactor could silently break the
 * preservation guarantees the prune algorithm advertises:
 *
 * - `userTurnIndices.size <= protectUserTurns` short-circuits to `emptySet()`.
 *   A refactor flipping `<=` to `<` would silently start pruning the
 *   latest user turn for sessions that have exactly N protected turns —
 *   silent UX regression with no error surface.
 * - Empty history must round-trip to `emptySet()`, not crash on
 *   `mapIndexedNotNull` empties or downstream `userTurnIndices[idx]`
 *   array bounds.
 * - Pre-window candidates are *only* `Part.Tool` with
 *   `ToolState.Completed`. A refactor dropping the `is ToolState.Completed`
 *   guard would start pruning Running / Pending / Failed tools — destroying
 *   the agent's view of in-flight work.
 * - Pre-window `Part.TimelineSnapshot` and `Part.Compaction` are NEVER
 *   candidates — they're load-bearing for "agent can reconstruct what
 *   the timeline looked like" and "the previous summary stays
 *   addressable", respectively.
 * - Boundary equality `fixed + kept == budget` MUST short-circuit
 *   (no drop), per the strict-greater logic on line `if (fixed + kept <= budget) break`.
 *   A `<` instead of `<=` here would over-aggressively prune at the
 *   exact threshold.
 *
 * **Why a dedicated boundaries file?** Cycles 309/310/313/314 banked
 * cross-coupling pins for **provider-side tables** (listModels,
 * LlmPricing, contextWindow). This file extends the same audit-pattern
 * to **algorithmic boundaries**: the prune logic has 5 tested happy
 * paths and 0 explicit boundary tests. The fastest place a refactor can
 * silently degrade UX is in the off-by-one and "is X type still
 * preserved?" edges that no test currently asserts.
 */
class CompactorPruneBoundariesTest {

    private val sid = SessionId("s")
    private val baseTime: Instant = Instant.fromEpochMilliseconds(0)
    private val model = ModelRef("fake", "x")

    private fun userMessage(idx: Int): Message.User =
        Message.User(MessageId("u-$idx"), sid, baseTime, agent = "default", model = model)

    private fun assistantMessage(idx: Int, parent: MessageId): Message.Assistant =
        Message.Assistant(
            id = MessageId("a-$idx"),
            sessionId = sid,
            createdAt = baseTime,
            parentId = parent,
            model = model,
            finish = FinishReason.TOOL_CALLS,
        )

    private fun completedTool(partId: String, messageId: MessageId, outputSize: Int): Part.Tool =
        Part.Tool(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            callId = CallId("c-$partId"),
            toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("x"))),
                outputForLlm = "x".repeat(outputSize),
                data = JsonObject(emptyMap()),
            ),
        )

    private fun runningTool(partId: String, messageId: MessageId): Part.Tool =
        Part.Tool(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            callId = CallId("c-$partId"),
            toolId = "echo",
            state = ToolState.Running(
                input = JsonObject(mapOf("text" to JsonPrimitive("x"))),
            ),
        )

    private fun pendingTool(partId: String, messageId: MessageId): Part.Tool =
        Part.Tool(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            callId = CallId("c-$partId"),
            toolId = "echo",
            state = ToolState.Pending,
        )

    private fun failedTool(partId: String, messageId: MessageId): Part.Tool =
        Part.Tool(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            callId = CallId("c-$partId"),
            toolId = "echo",
            state = ToolState.Failed(
                input = JsonObject(mapOf("text" to JsonPrimitive("x"))),
                message = "boom",
            ),
        )

    private fun timelineSnapshot(partId: String, messageId: MessageId): Part.TimelineSnapshot =
        Part.TimelineSnapshot(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            timeline = Timeline(),
        )

    private fun compactionPart(partId: String, messageId: MessageId): Part.Compaction =
        Part.Compaction(
            id = PartId(partId),
            messageId = messageId,
            sessionId = sid,
            createdAt = baseTime,
            replacedFromMessageId = MessageId("u-old"),
            replacedToMessageId = MessageId("a-old"),
            summary = "x".repeat(100),
        )

    private fun compactor(protectUserTurns: Int = 1, pruneProtectTokens: Int = 1_000): Compactor {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        return Compactor(
            provider = FakeProvider(emptyList()),
            store = store,
            bus = EventBus(),
            clock = Clock.System,
            protectUserTurns = protectUserTurns,
            pruneProtectTokens = pruneProtectTokens,
        )
    }

    // ── Short-circuit boundaries (userTurnIndices.size vs protectUserTurns) ────

    @Test fun emptyHistoryReturnsEmptyDropSet() {
        val c = compactor()
        assertEquals(emptySet(), c.prune(emptyList()))
    }

    @Test fun zeroUserTurnsReturnsEmptyDropSet() {
        // History with only assistant messages (no user turns) — the
        // `userTurnIndices.size <= protectUserTurns` guard should
        // short-circuit even when there are completed tools sitting
        // around. Documents the invariant that prune never operates
        // on a session with no user turns.
        val a = assistantMessage(1, MessageId("ghost"))
        val tool = completedTool("t1", a.id, outputSize = 5_000)
        val history = listOf(MessageWithParts(a, listOf(tool)))
        val c = compactor()
        assertEquals(
            emptySet(),
            c.prune(history),
            "Zero user turns MUST short-circuit; no Completed tool should be pruned",
        )
    }

    @Test fun exactlyProtectUserTurnsCountReturnsEmptyDropSet() {
        // Boundary: userTurnIndices.size == protectUserTurns. The
        // condition is `<=`, so equality short-circuits — preserving
        // everything. Drift to `<` would silently start pruning at the
        // exact threshold (cycle-N session where N = protectUserTurns
        // would suddenly start losing its own first user turn).
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val tool = completedTool("t1", a1.id, outputSize = 5_000)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(tool)),
        )
        val c = compactor(protectUserTurns = 1)
        assertEquals(
            emptySet(),
            c.prune(history),
            "userTurnIndices.size == protectUserTurns MUST preserve everything (<= guard)",
        )
    }

    @Test fun fewerUserTurnsThanProtectCountReturnsEmptyDropSet() {
        // 1 user turn but protectUserTurns = 3. Same short-circuit
        // path as the equality case but with explicit "less than"
        // semantics — pin the comparison direction explicitly so a
        // refactor confusing `<=` with `>=` surfaces here.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val tool = completedTool("t1", a1.id, outputSize = 5_000)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(tool)),
        )
        val c = compactor(protectUserTurns = 3)
        assertEquals(emptySet(), c.prune(history))
    }

    // ── Candidate-detection guards (only Completed tools are draftable) ────

    @Test fun runningToolInPreWindowIsNotACandidate() {
        // ToolState.Running is in-flight work; if a refactor dropped
        // the `is ToolState.Completed` guard, the agent would prune
        // its view of work it's still mid-execution on — destroying
        // tool-result attribution.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val running = runningTool("running-t1", a1.id)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(running)),
            MessageWithParts(u2, emptyList()),
        )
        // Tight budget that would normally drop pre-window tools.
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        assertEquals(
            emptySet(),
            c.prune(history),
            "Running tools are NEVER candidates — the agent has unfinished work attached",
        )
    }

    @Test fun pendingToolInPreWindowIsNotACandidate() {
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val pending = pendingTool("pending-t1", a1.id)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(pending)),
            MessageWithParts(u2, emptyList()),
        )
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        assertEquals(
            emptySet(),
            c.prune(history),
            "Pending tools are NEVER candidates",
        )
    }

    @Test fun failedToolInPreWindowIsNotACandidate() {
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val failed = failedTool("failed-t1", a1.id)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(failed)),
            MessageWithParts(u2, emptyList()),
        )
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        assertEquals(
            emptySet(),
            c.prune(history),
            "Failed tools are NEVER candidates — error context is load-bearing for retry/UX",
        )
    }

    @Test fun timelineSnapshotInPreWindowIsNotACandidate() {
        // Part.TimelineSnapshot is the agent's reconstruction surface
        // for "what did the timeline look like at step N". Pruning it
        // breaks the consistency guarantee VISION §3.4 relies on.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val snapshot = timelineSnapshot("snap-1", a1.id)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(snapshot)),
            MessageWithParts(u2, emptyList()),
        )
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        assertEquals(
            emptySet(),
            c.prune(history),
            "TimelineSnapshot parts are NEVER candidates — load-bearing for reconstruct invariant",
        )
    }

    @Test fun compactionPartInPreWindowIsNotACandidate() {
        // Part.Compaction is THE summary of older turns. Pruning it
        // would erase the only thing replacing the already-pruned
        // turns — strictly worse than not compacting at all.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val oldSummary = compactionPart("old-summary", a1.id)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(oldSummary)),
            MessageWithParts(u2, emptyList()),
        )
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        assertEquals(
            emptySet(),
            c.prune(history),
            "Compaction summaries are NEVER candidates — they replace already-pruned content",
        )
    }

    // ── Budget-equality boundary ──────────────────────────────────

    @Test fun budgetExactlyEqualsKeptHonoursLessThanOrEqualGuard() {
        // The drop loop uses `if (fixed + kept <= budget) break`. At
        // exact equality we stop dropping. Pin: at the budget boundary,
        // no further candidate is dropped beyond what's necessary to
        // get under-or-equal. A refactor flipping `<=` to `<` would
        // silently drop one more candidate than required at every
        // exact-fit budget.
        //
        // Setup: pre-window has two completed tools (each ~5 tokens
        // by byte heuristic; outputForLlm is "xxxxx" = 5 chars / 4 bytes/token
        // ≈ 1 token). Budget set so fixed + ONE candidate exactly
        // matches budget — second candidate must NOT be dropped.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val small1 = completedTool("small-1", a1.id, outputSize = 4)
        val small2 = completedTool("small-2", a1.id, outputSize = 4)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(small1, small2)),
            MessageWithParts(u2, emptyList()),
        )
        // Walk via the actual TokenEstimator since byte-heuristic
        // overhead per part is not zero. Budget = total - 1 small,
        // so exactly one drop is required. The second small candidate
        // must remain kept.
        val totalAllParts = TokenEstimator.forHistory(history)
        val tokensPerSmall = TokenEstimator.forPart(small1)
        val budget = totalAllParts - tokensPerSmall // need exactly 1 drop
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = budget)
        val drop = c.prune(history)
        assertEquals(
            1,
            drop.size,
            "Exact-fit budget MUST require exactly 1 drop (≤ guard), got drop.size=${drop.size} " +
                "out of 2 candidates; total=$totalAllParts budget=$budget perSmall=$tokensPerSmall",
        )
    }

    @Test fun fixedExceedsBudgetButNoCandidatesReturnsEmpty() {
        // No completed tools in pre-window — even though budget is
        // tight, there's nothing droppable, so empty set. Pins that
        // prune NEVER drops non-tool parts no matter how over-budget
        // we are.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        // Big text part — counts toward fixed, NOT candidate.
        val bigText = Part.Text(
            id = PartId("big-text"),
            messageId = a1.id,
            sessionId = sid,
            createdAt = baseTime,
            text = "x".repeat(10_000),
        )
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(bigText)),
            MessageWithParts(u2, emptyList()),
        )
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = 10)
        val drop = c.prune(history)
        assertTrue(
            drop.isEmpty(),
            "No completed-tool candidates = empty drop, even when over-budget; got drop=$drop",
        )
    }

    // ── Biggest-first ordering re-pin (anti-regression) ────────────

    @Test fun biggestCandidateDroppedFirstWhenSingleDropFits() {
        // Re-pin the size-descending sort. Existing CompactorTest covers
        // this happy-path; this one keeps the boundary file self-contained
        // for future readers reviewing prune algorithm invariants in one
        // place.
        val u1 = userMessage(1)
        val a1 = assistantMessage(1, u1.id)
        val tiny = completedTool("tiny", a1.id, outputSize = 4)
        val huge = completedTool("huge", a1.id, outputSize = 8_000)
        val u2 = userMessage(2)
        val history = listOf(
            MessageWithParts(u1, emptyList()),
            MessageWithParts(a1, listOf(tiny, huge)),
            MessageWithParts(u2, emptyList()),
        )
        // Budget that requires dropping just the huge one to fit.
        val total = TokenEstimator.forHistory(history)
        val hugeTokens = TokenEstimator.forPart(huge)
        val budget = total - hugeTokens
        val c = compactor(protectUserTurns = 1, pruneProtectTokens = budget)
        val drop = c.prune(history)
        assertEquals(
            setOf(huge.id),
            drop,
            "Biggest candidate MUST be dropped first; tiny stays. drop=$drop",
        )
    }
}
