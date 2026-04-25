package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.SessionId
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * One per-message token row inside a [TokenEstimateRow] breakdown.
 * Populated only when the caller passed `includeBreakdown=true`.
 */
@Serializable
data class TokenBreakdownEntry(
    val id: String,
    /** `"user"` | `"assistant"`. */
    val role: String,
    /** Heuristic token cost via [TokenEstimator.forPart] summed across the message's parts. */
    val tokens: Int,
)

/**
 * `select=token_estimate` — single-row session-weight probe.
 *
 * Cycle 141 absorbed the standalone `estimate_session_tokens` tool
 * here, mirroring the cycle 137-140 fold series. Same pattern as
 * `select=cache_stats` / `select=context_pressure` / `select=spend_summary`:
 * single-session aggregate that wraps a [SessionStore] walk.
 *
 * Pre-compaction probe — answers "is this session heavy enough that
 * my next turn will trigger compaction?" before the agent commits to
 * a potentially-compacting call, or explains "why did compaction
 * fire?" after the fact. Wraps [TokenEstimator.forHistory] (the same
 * heuristic the compactor uses), so the totals here line up with what
 * compaction actually sees. Real provider tokens (BPE / SentencePiece)
 * are reported on assistant rows as `tokens_input` / `tokens_output`
 * after a turn completes — surface those via `select=messages` whenever
 * available; this tool is the *pre-turn* estimate.
 *
 * `largestMessageTokens` is surfaced even in terse mode because it
 * answers a distinct question from "total": a session with 50 small
 * user prompts and one 20k-token tool result looks very different to
 * compact than one with 50 evenly-sized messages, and the agent can
 * make that call without paying for the full breakdown.
 */
@Serializable
data class TokenEstimateRow(
    val sessionId: String,
    val messageCount: Int,
    /** Heuristic total via [TokenEstimator.forHistory]. */
    val totalTokens: Int,
    /** Max per-message token count across the session (0 for empty sessions). */
    val largestMessageTokens: Int,
    /**
     * Per-message rows; populated only when `includeBreakdown=true`.
     * Most-recent first so "which message is the fatty?" reads
     * naturally from the tail.
     */
    val breakdown: List<TokenBreakdownEntry>? = null,
)

internal suspend fun runTokenEstimateQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_TOKEN_ESTIMATE}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)
    val session = sessions.getSession(sid)
        ?: error(
            "Session $sessionIdStr not found. Call session_query(select=sessions) to discover valid ids.",
        )

    // Match the Agent loop's view by hydrating every part — the loop
    // strips compacted parts before building the prompt, but our
    // pre-turn estimate models the post-strip total via
    // TokenEstimator.forHistory below.
    val mwps: List<MessageWithParts> = sessions.listMessagesWithParts(sid)

    val perMessage = mwps.map { mwp ->
        val tokens = mwp.parts.sumOf { TokenEstimator.forPart(it) }
        val role = when (mwp.message) {
            is Message.User -> "user"
            is Message.Assistant -> "assistant"
        }
        TokenBreakdownEntry(id = mwp.message.id.value, role = role, tokens = tokens)
    }

    val total = TokenEstimator.forHistory(mwps)
    val largest = perMessage.maxOfOrNull { it.tokens } ?: 0

    val wantBreakdown = input.includeBreakdown == true
    val breakdownMostRecentFirst = if (wantBreakdown) {
        perMessage.zip(mwps.map { it.message.createdAt.toEpochMilliseconds() })
            .sortedByDescending { it.second }
            .map { it.first }
    } else {
        null
    }

    val row = TokenEstimateRow(
        sessionId = session.id.value,
        messageCount = mwps.size,
        totalTokens = total,
        largestMessageTokens = largest,
        breakdown = breakdownMostRecentFirst,
    )
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(TokenEstimateRow.serializer()),
        listOf(row),
    ) as JsonArray

    val baseSummary =
        "Session ${session.id.value} '${session.title}': ${mwps.size} message(s), " +
            "total ~$total token(s). Largest message: ~$largest token(s)."
    val summary = if (wantBreakdown && breakdownMostRecentFirst != null && breakdownMostRecentFirst.isNotEmpty()) {
        val top = breakdownMostRecentFirst.sortedByDescending { it.tokens }.take(3)
            .joinToString(", ") { "${it.role}/${it.id} ~${it.tokens}" }
        "$baseSummary Top: $top."
    } else {
        baseSummary
    }

    return ToolResult(
        title = "session_query token_estimate ~$total",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_TOKEN_ESTIMATE,
            total = 1,
            returned = 1,
            rows = jsonRows,
        ),
    )
}
