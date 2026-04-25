package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.SessionId
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row shape for `select=text_search`. One match per row; the snippet
 * is a centred ±[SNIPPET_PAD] character window around the first hit
 * inside the captured text. Same `messageId` may appear multiple
 * times if the message has multiple text parts that match — each part
 * surfaces as its own row.
 *
 * `matchOffset` is the byte offset of the first match within the
 * surfaced [snippet] (NOT within the original part text), so callers
 * can highlight the hit without re-finding it.
 */
@Serializable
data class TextSearchMatchRow(
    val messageId: String,
    val sessionId: String,
    val partId: String,
    val snippet: String,
    val matchOffset: Int,
    val createdAtEpochMs: Long,
)

/**
 * `select=text_search` — substring grep over `Part.Text` content.
 *
 * Backstory: agent re-attaching to old sessions or operator looking
 * for "the session where I asked about X" had no read path —
 * fallback was hand-grepping `~/.talevia/talevia.db` SQLite. This
 * pushes the search server-side via [SessionStore.searchTextInParts]
 * so all five containers expose the same surface.
 *
 * Cross-session by default — pass `sessionId` to scope. Compacted
 * parts excluded by the store; matches are from currently-active
 * context only. Sorted newest-first (matches the recorder's
 * convention) so the operator's "most recently I said X" cadence
 * surfaces first.
 *
 * Empty/blank query is rejected loud — silently returning every part
 * would spike token cost; an empty search is a typo.
 */
internal suspend fun runTextSearchQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val query = input.query?.takeIf { it.isNotBlank() }
        ?: error(
            "select='${SessionQueryTool.SELECT_TEXT_SEARCH}' requires non-blank `query`. " +
                "Pass the substring you want to find in past messages.",
        )
    val sid = input.sessionId?.let { SessionId(it) }

    val parts = sessions.searchTextInParts(query, sid, limit = limit, offset = offset)
    val rows = parts.map { part ->
        val (snippet, matchOffset) = snippetAround(part.text, query)
        TextSearchMatchRow(
            messageId = part.messageId.value,
            sessionId = part.sessionId.value,
            partId = part.id.value,
            snippet = snippet,
            matchOffset = matchOffset,
            createdAtEpochMs = part.createdAt.toEpochMilliseconds(),
        )
    }

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(TextSearchMatchRow.serializer()),
        rows,
    ) as JsonArray

    val scopeNote = if (sid == null) "across all sessions" else "in session ${sid.value}"
    val summary = if (rows.isEmpty()) {
        "No text-part matches for query '$query' $scopeNote."
    } else {
        "Found ${rows.size} text-part match(es) for '$query' $scopeNote " +
            "(returning ${rows.size} from offset=$offset, limit=$limit)."
    }

    return ToolResult(
        title = "session_query text_search '$query' (${rows.size} match${if (rows.size == 1) "" else "es"})",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_TEXT_SEARCH,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}

private const val SNIPPET_PAD = 60

/**
 * Pull a centred substring around the first hit. Case-insensitive
 * match (mirrors SQL LIKE); `matchOffset` is the position of the
 * match within the returned snippet (so a UI can highlight it without
 * re-searching). Returns the original text + matchOffset=-1 when the
 * SQL row matched but the search-needle isn't in the decoded text
 * (shouldn't happen for `Part.Text.text` but defensive).
 */
private fun snippetAround(text: String, query: String): Pair<String, Int> {
    val lcText = text.lowercase()
    val lcQuery = query.lowercase()
    val idx = lcText.indexOf(lcQuery)
    if (idx < 0) return text to -1
    val start = (idx - SNIPPET_PAD).coerceAtLeast(0)
    val end = (idx + lcQuery.length + SNIPPET_PAD).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    val snippet = prefix + text.substring(start, end) + suffix
    val snippetMatchOffset = prefix.length + (idx - start)
    return snippet to snippetMatchOffset
}
