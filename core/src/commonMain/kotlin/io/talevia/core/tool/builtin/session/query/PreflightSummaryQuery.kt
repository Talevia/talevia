package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.compaction.DEFAULT_COMPACTION_TOKEN_THRESHOLD
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Single-row "what's my situation right now?" snapshot for the agent's
 * planning step.
 *
 * Today, an agent that wants the equivalent picture has to issue four
 * separate `session_query` calls and stitch the rows together —
 * `context_pressure` + `fallback_history` + `cancellation_history` +
 * `active_run_summary` + (now) `permission_history` for pending asks.
 * Five queries × token-spec overhead × per-turn cost. This select
 * collapses the same data into one row so plan-time pre-flight is one
 * tool call.
 *
 * Fields rationale:
 *  - `contextEstimate` / `contextThreshold` / `contextRatio` /
 *    `contextOverThreshold` mirror `select=context_pressure`'s shape
 *    so callers familiar with that select can read the same numbers
 *    without re-learning. `marginTokens` carries the over-threshold
 *    sign (negative when over).
 *  - `runState` is the current [AgentRunState] (string form so iOS /
 *    JSON consumers don't have to discriminate the sealed class). Null
 *    when no run has fired yet.
 *  - `lastRetryAttempt` is the latest state-transition's
 *    `retryAttempt` field (per `RunStateTransitionRow`) — answers "am
 *    I retrying or fresh?". Null when never retried.
 *  - `fallbackHopCount` + `lastFallbackEpochMs` summarise the session's
 *    provider-fallback ledger. Zero hops is the common case.
 *  - `lastCancelledMessageId` + `lastCancelledEpochMs` flag the most
 *    recent cancelled turn (Ctrl-C / IDE abort) — relevant when the
 *    agent is reasoning about "did the user actually want me to do
 *    that or did they kill it?".
 *  - `pendingPermissionAskCount` counts permission-asked entries
 *    awaiting reply. Non-zero means a previous turn is blocked
 *    waiting on the user — the agent shouldn't dispatch tools that
 *    would queue another ask.
 *
 * Every field is optional in spirit (most carry sensible zeros /
 * nulls), so a session with no runs / no fallbacks / no cancels reads
 * as a clean "everything's idle" row rather than failing.
 */
@Serializable
data class PreflightSummaryRow(
    val sessionId: String,
    /** TokenEstimator on `listMessagesWithParts(includeCompacted=false)`. */
    val contextEstimate: Int,
    val contextThreshold: Int,
    /** `contextEstimate / contextThreshold` un-clamped — > 1.0 when over. */
    val contextRatio: Double,
    /** `threshold - estimate` — negative when over. */
    val contextMarginTokens: Int,
    val contextOverThreshold: Boolean,
    /** Latest [io.talevia.core.session.AgentRunState] for this session, stringified. Null when no run yet. */
    val runState: String? = null,
    /** Latest state-transition retryAttempt (per RunStateTransitionRow). Null when never retried. */
    val lastRetryAttempt: Int? = null,
    /** Number of provider-fallback hops captured for this session. */
    val fallbackHopCount: Int,
    val lastFallbackEpochMs: Long? = null,
    val lastFallbackFromProviderId: String? = null,
    val lastFallbackToProviderId: String? = null,
    /** Most-recent cancelled assistant turn, if any. */
    val lastCancelledMessageId: String? = null,
    val lastCancelledEpochMs: Long? = null,
    /** PermissionAsked entries still awaiting reply (`accepted == null`). */
    val pendingPermissionAskCount: Int,
)

/**
 * `select=preflight_summary` — single-row aggregate over four other
 * query lanes. Plan-time only; no mutations.
 *
 * Optional dependencies: agentStates / fallbackTracker /
 * permissionHistory all default to "absent → field reports zeros".
 * Test rigs without those wired don't fail; production containers
 * have all three.
 */
internal suspend fun runPreflightSummaryQuery(
    sessions: SessionStore,
    agentStates: AgentRunStateTracker?,
    fallbackTracker: AgentProviderFallbackTracker?,
    permissionHistory: PermissionHistoryRecorder?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_PREFLIGHT_SUMMARY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)
    sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )

    // Context pressure: same slice [Compactor] evaluates.
    val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
    val contextEstimate = TokenEstimator.forHistory(history)
    val threshold = DEFAULT_COMPACTION_TOKEN_THRESHOLD
    val ratio = if (threshold > 0) contextEstimate.toDouble() / threshold.toDouble() else 0.0

    // Run state + last retry attempt: from the tracker's latest
    // transition. The `currentState` fast-path doesn't carry retry
    // attempt; use `history(...).lastOrNull()` for the joined view.
    val runState = agentStates?.currentState(sid)?.let { it::class.simpleName }
    val lastRetryAttempt = agentStates?.history(sid)?.lastOrNull()?.retryAttempt

    // Fallback summary.
    val hops = fallbackTracker?.hops(sid).orEmpty()
    val lastHop = hops.lastOrNull()

    // Last cancelled turn — walk listMessages once.
    val cancelledAssistant = sessions.listMessages(sid)
        .asSequence()
        .filterIsInstance<Message.Assistant>()
        .filter { it.finish == FinishReason.CANCELLED }
        .lastOrNull()

    // Pending permission asks — entries where the recorder hasn't yet
    // observed a Replied event matching the requestId.
    val pendingPermissionAsks = permissionHistory?.snapshot(sid.value)
        ?.count { it.accepted == null } ?: 0

    val row = PreflightSummaryRow(
        sessionId = sid.value,
        contextEstimate = contextEstimate,
        contextThreshold = threshold,
        contextRatio = ratio,
        contextMarginTokens = threshold - contextEstimate,
        contextOverThreshold = contextEstimate >= threshold,
        runState = runState,
        lastRetryAttempt = lastRetryAttempt,
        fallbackHopCount = hops.size,
        lastFallbackEpochMs = lastHop?.epochMs,
        lastFallbackFromProviderId = lastHop?.fromProviderId,
        lastFallbackToProviderId = lastHop?.toProviderId,
        lastCancelledMessageId = cancelledAssistant?.id?.value,
        lastCancelledEpochMs = cancelledAssistant?.createdAt?.toEpochMilliseconds(),
        pendingPermissionAskCount = pendingPermissionAsks,
    )

    val rows = encodeRows(
        ListSerializer(PreflightSummaryRow.serializer()),
        listOf(row),
    )

    val pct = (ratio * 100.0).toString().take(5)
    val notes = buildList {
        if (row.contextOverThreshold) {
            add("CONTEXT OVER (margin=${row.contextMarginTokens} tokens)")
        } else {
            add("ctx=${row.contextEstimate}/${row.contextThreshold} (${pct}%)")
        }
        row.runState?.let { add("state=$it") }
        if (row.fallbackHopCount > 0) {
            add(
                "fallback=${row.fallbackHopCount} hops (last ${row.lastFallbackFromProviderId}" +
                    "→${row.lastFallbackToProviderId})",
            )
        }
        row.lastCancelledMessageId?.let { add("lastCancel=$it") }
        if (row.pendingPermissionAskCount > 0) {
            add("pendingPermissionAsks=${row.pendingPermissionAskCount}")
        }
        row.lastRetryAttempt?.let { add("lastRetryAttempt=$it") }
    }

    return ToolResult(
        title = "session_query preflight_summary ${sid.value}",
        outputForLlm = "Pre-flight summary for ${sid.value}: " + notes.joinToString(", ") + ".",
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_PREFLIGHT_SUMMARY,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
