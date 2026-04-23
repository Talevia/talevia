package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=cache_stats` — single-row prompt-cache utilisation for one session.
 * Walks the session's assistant messages and sums their [TokenUsage]. Providers
 * normalise `input` to include both cached and uncached input tokens, so the
 * hit ratio is `cacheReadTokens / totalInputTokens` regardless of provider.
 */
@Serializable data class CacheStatsRow(
    val sessionId: String,
    /** Number of assistant messages contributing to the aggregate. Zero → the session has had no assistant turns yet. */
    val assistantMessageCount: Int,
    /** Sum of `TokenUsage.input` across assistant messages (cached + uncached). */
    val totalInputTokens: Long,
    /** Sum of `TokenUsage.cacheRead` — input tokens served from the provider's cache. */
    val cacheReadTokens: Long,
    /** Sum of `TokenUsage.cacheWrite` — input tokens newly written to the cache. */
    val cacheWriteTokens: Long,
    /** `cacheReadTokens / totalInputTokens`, clamped to [0.0, 1.0]. Zero when totalInputTokens is zero. */
    val hitRatio: Double,
)

/**
 * `select=cache_stats` — single-row prompt-cache utilisation aggregate
 * for one session. Walks the session's assistant messages and sums
 * their [io.talevia.core.session.TokenUsage] fields; the hit ratio is
 * `cacheReadTokens / totalInputTokens` (provider-normalised: every
 * provider reports `input` as the cached+uncached total, so the ratio
 * is comparable across Anthropic, OpenAI, Gemini without per-provider
 * translation).
 *
 * Debug lane (VISION §5.4): agents that don't see any cache hits over
 * many turns usually have a session-scoped cache key churning (model
 * changed mid-run, prompt-cache key rotated, tool-spec bundle mutated
 * every turn). Today that signal required grepping bus events or
 * counting cache tokens by hand. `session_query(select=cache_stats)`
 * surfaces it in one call.
 *
 * Scope: assistant messages only. User / tool-result messages don't
 * carry token counts; the provider attributes cost to the assistant
 * response they powered. Zero assistant messages → zero everything,
 * `hitRatio=0.0` (not `NaN`).
 */
internal suspend fun runCacheStatsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_CACHE_STATS}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val messages = sessions.listMessages(session.id)
    var assistantCount = 0
    var totalInput = 0L
    var cacheRead = 0L
    var cacheWrite = 0L
    for (msg in messages) {
        if (msg is Message.Assistant) {
            assistantCount++
            val tokens = msg.tokens
            totalInput += tokens.input
            cacheRead += tokens.cacheRead
            cacheWrite += tokens.cacheWrite
        }
    }
    val hitRatio = if (totalInput > 0L) {
        (cacheRead.toDouble() / totalInput.toDouble()).coerceIn(0.0, 1.0)
    } else {
        0.0
    }

    val row = CacheStatsRow(
        sessionId = sid.value,
        assistantMessageCount = assistantCount,
        totalInputTokens = totalInput,
        cacheReadTokens = cacheRead,
        cacheWriteTokens = cacheWrite,
        hitRatio = hitRatio,
    )
    val rows = encodeRows(
        ListSerializer(CacheStatsRow.serializer()),
        listOf(row),
    )
    val pct = (hitRatio * 100.0).toString().take(5)
    val summary = "Session ${sid.value} cache stats: $assistantCount assistant message(s), " +
        "totalInput=$totalInput, cacheRead=$cacheRead, cacheWrite=$cacheWrite, hitRatio=${pct}%."
    return ToolResult(
        title = "session_query cache_stats ${sid.value} (${pct}%)",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_CACHE_STATS,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
