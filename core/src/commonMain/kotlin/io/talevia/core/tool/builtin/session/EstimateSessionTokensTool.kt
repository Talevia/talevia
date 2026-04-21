package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Pre-compaction session-weight probe. Complements the text-only
 * `estimate_tokens` (which sizes a candidate string) — this one sizes the
 * *whole existing session*, so the agent can answer "is this session heavy
 * enough that my next turn will trigger compaction?" before it commits to a
 * potentially-compacting call, or explain to the user *why* a compaction
 * kicked in after the fact ("the session sat at ~80k heuristic tokens before
 * my turn — that crossed the threshold").
 *
 * Implementation wraps [TokenEstimator.forHistory] — the same heuristic the
 * compactor uses for its "should we compact?" trigger, so the numbers here
 * line up with what compaction actually sees. Real provider tokens (BPE /
 * SentencePiece) are reported on assistant rows as `tokens_input` /
 * `tokens_output` after a turn completes; surface those via `list_messages`
 * or `describe_session` whenever available — this tool is the *pre-turn*
 * estimate.
 *
 * Two output modes:
 *  - Terse (default, `includeBreakdown = null | false`): totals only —
 *    `totalTokens`, `messageCount`, `largestMessageTokens`.
 *  - Breakdown (`includeBreakdown = true`): per-message rows with id / role
 *    / tokens. Useful for debugging "which message is the fatty?" on a
 *    session that's unexpectedly heavy. Rows are most-recent first so the
 *    usual "tail first" read matches `list_messages`.
 *
 * `largestMessageTokens` is surfaced even in terse mode because it answers
 * a distinct question from "total": a session with 50 small user prompts
 * and one 20k-token tool result looks very different to compact than one
 * with 50 evenly-sized messages, and the agent can make that call without
 * paying for the full breakdown.
 *
 * Read-only, permission `session.read` (reuses the session-lane keyword,
 * default ALLOW in [io.talevia.core.permission.DefaultPermissionRuleset]).
 */
class EstimateSessionTokensTool(
    private val sessions: SessionStore,
) : Tool<EstimateSessionTokensTool.Input, EstimateSessionTokensTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        /**
         * When true, include per-message token rows in the output. Default
         * false keeps the response terse so a 1000-message session doesn't
         * blow up the tool-result payload — agents only reach for the
         * breakdown when they're debugging *which* message is heavy.
         */
        val includeBreakdown: Boolean? = null,
    )

    @Serializable data class MessageBreakdown(
        val id: String,
        /** `"user"` | `"assistant"`. */
        val role: String,
        /** Heuristic token cost via [TokenEstimator.forPart] summed across the message's parts. */
        val tokens: Int,
    )

    @Serializable data class Output(
        val sessionId: String,
        val messageCount: Int,
        /** Heuristic via [TokenEstimator.forHistory]. */
        val totalTokens: Int,
        /** Max per-message token count across the session (0 for empty sessions). */
        val largestMessageTokens: Int,
        /** Per-message rows; only populated when `includeBreakdown == true`. Most-recent first. */
        val messages: List<MessageBreakdown>? = null,
    )

    override val id: String = "estimate_session_tokens"
    override val helpText: String =
        "Estimate how heavy an existing session is, in heuristic tokens. Answers \"will my next " +
            "turn trigger compaction?\" before committing to the call, or explains \"why did " +
            "compaction fire?\" after one. Uses the same ~4 chars/token heuristic that drives " +
            "the compactor's trigger — the real provider tokenizer (BPE for OpenAI, SentencePiece " +
            "for Anthropic) will disagree modestly. For the *actual* per-turn token counts, read " +
            "`tokens_input` / `tokens_output` on assistant rows via `list_messages` / " +
            "`describe_session`. Default terse mode returns totals + largest-message size; pass " +
            "`includeBreakdown=true` for per-message rows when you're hunting the fat one."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session id from list_sessions.")
            }
            putJsonObject("includeBreakdown") {
                put("type", "boolean")
                put(
                    "description",
                    "When true, include per-message token rows (most-recent first). Default " +
                        "false for a terse totals-only response — large sessions would produce " +
                        "a big payload otherwise.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        // Use the post-compaction view by default (matches what the next LLM turn
        // will actually see) — `listMessagesWithParts` defaults `includeCompacted = true`,
        // but the agent loop strips compacted parts before building the prompt. We
        // match the Agent's view by asking for the full hydrated history; the
        // per-message tokens will naturally reflect every part that's still in the row.
        val mwps: List<MessageWithParts> = sessions.listMessagesWithParts(sid)

        // Per-message tokens in chronological order (matches store ordering).
        val perMessage = mwps.map { mwp ->
            val tokens = mwp.parts.sumOf { TokenEstimator.forPart(it) }
            val role = when (mwp.message) {
                is Message.User -> "user"
                is Message.Assistant -> "assistant"
            }
            MessageBreakdown(id = mwp.message.id.value, role = role, tokens = tokens)
        }

        val total = TokenEstimator.forHistory(mwps)
        val largest = perMessage.maxOfOrNull { it.tokens } ?: 0

        val wantBreakdown = input.includeBreakdown == true
        // Most-recent first so "which message is fat?" reads naturally from the tail.
        val breakdownMostRecentFirst =
            if (wantBreakdown) {
                perMessage.zip(mwps.map { it.message.createdAt.toEpochMilliseconds() })
                    .sortedByDescending { it.second }
                    .map { it.first }
            } else {
                null
            }

        val out = Output(
            sessionId = session.id.value,
            messageCount = mwps.size,
            totalTokens = total,
            largestMessageTokens = largest,
            messages = breakdownMostRecentFirst,
        )

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
            title = "estimate session tokens (~$total)",
            outputForLlm = summary,
            data = out,
        )
    }
}
