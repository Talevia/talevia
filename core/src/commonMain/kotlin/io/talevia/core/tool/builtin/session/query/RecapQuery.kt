package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Single-row "what happened in this session?" summary — the
 * orientation row both an agent re-attaching to an old session and
 * an operator opening the CLI for a continuation read first.
 *
 * Reuses what the heavier per-axis selects already give:
 *  - turn / token / cost: same numbers as [SpendSummaryRow] +
 *    [SessionMetadataRow], but in one row
 *  - distinctToolsUsed: collected from `Part.Tool` instances on
 *    every message in the session
 *  - lastModelId: the most recent assistant message's model
 *  - firstAt / lastAt: createdAt of the first user message + most
 *    recent message (any role)
 *
 * Cost aggregation reuses the same lockfile-by-sessionId walk
 * [runSpendQuery] uses. When the session's project no longer
 * resolves (deleted, closed bundle), [totalCostCents] is 0 and
 * [projectResolved] is false — same convention as spend.
 *
 * Compacted parts ARE included in distinctToolsUsed (`list*WithParts`
 * with `includeCompacted=true`) so the orientation row reflects the
 * full session history, not just the live window.
 */
@Serializable
data class SessionRecapRow(
    val sessionId: String,
    val projectId: String,
    val turnCount: Int,
    val totalTokensIn: Long,
    val totalTokensOut: Long,
    val totalCostCents: Long,
    val unknownCostEntries: Int,
    val distinctToolsUsed: List<String>,
    val lastModelId: String?,
    val firstAtEpochMs: Long?,
    val lastAtEpochMs: Long?,
    val projectResolved: Boolean,
)

/**
 * `select=recap` — single-row session orientation summary.
 *
 * Backstory: the bullet `session-query-recap` cited the gap that an
 * agent re-attaching to an old session has to issue 3+ separate
 * queries (messages + parts + spend) and stitch the answer
 * client-side. This collapses the same data into one row.
 *
 * `turnCount` counts assistant messages (not user — the user-side
 * is symmetrical and would inflate the count). Token totals roll up
 * from each assistant message's `tokens` field; same numbers
 * `select=session_metadata` reports, just compacted into the recap
 * shape.
 *
 * `distinctToolsUsed` is a small list (≤ tool registry size) so we
 * don't paginate. Sorted alphabetically for stable diffing.
 *
 * Why include `unknownCostEntries`: without it the agent sees
 * `totalCostCents=0` and can't distinguish "no AIGC calls" from "an
 * AIGC pricing rule is missing". Same three-state convention as
 * AigcPricing.estimateCents.
 */
internal suspend fun runSessionRecapQuery(
    sessions: SessionStore,
    projects: ProjectStore?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_RECAP}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to " +
                "discover valid session ids.",
        )

    val messagesWithParts = sessions.listMessagesWithParts(sid, includeCompacted = true)

    val assistantMessages = messagesWithParts.mapNotNull { it.message as? Message.Assistant }
    val turnCount = assistantMessages.size

    var totalTokensIn = 0L
    var totalTokensOut = 0L
    val toolIdsSeen = mutableSetOf<String>()
    var lastModelId: String? = null
    for (mwp in messagesWithParts) {
        val msg = mwp.message
        if (msg is Message.Assistant) {
            totalTokensIn += msg.tokens.input
            totalTokensOut += msg.tokens.output
            // listMessagesWithParts is oldest-first; the last assistant
            // message we walk is the most recent, so we overwrite.
            lastModelId = "${msg.model.providerId}/${msg.model.modelId}"
        }
        for (part in mwp.parts) {
            if (part is Part.Tool) toolIdsSeen += part.toolId
        }
    }

    val firstAt = messagesWithParts.firstOrNull()?.message?.createdAt?.toEpochMilliseconds()
    val lastAt = messagesWithParts.lastOrNull()?.message?.createdAt?.toEpochMilliseconds()

    // Cost: same lockfile-by-sessionId walk runSpendQuery does. Re-
    // implementing inline (rather than calling runSpendQuery) keeps the
    // recap row's optional-project fallback in lockstep with the rest of
    // the row's locally-computed fields.
    val resolvedProject = projects?.get(session.projectId)
    var totalCostCents = 0L
    var unknownCostEntries = 0
    if (resolvedProject != null) {
        for (entry in resolvedProject.lockfile.entries) {
            if (entry.sessionId != sid.value) continue
            val cents = entry.costCents
            if (cents != null) totalCostCents += cents else unknownCostEntries += 1
        }
    }

    val row = SessionRecapRow(
        sessionId = sid.value,
        projectId = session.projectId.value,
        turnCount = turnCount,
        totalTokensIn = totalTokensIn,
        totalTokensOut = totalTokensOut,
        totalCostCents = totalCostCents,
        unknownCostEntries = unknownCostEntries,
        distinctToolsUsed = toolIdsSeen.sorted(),
        lastModelId = lastModelId,
        firstAtEpochMs = firstAt,
        lastAtEpochMs = lastAt,
        projectResolved = resolvedProject != null,
    )

    val rows = encodeRows(ListSerializer(SessionRecapRow.serializer()), listOf(row))

    val toolNote = if (toolIdsSeen.isEmpty()) "no tools" else "${toolIdsSeen.size} distinct tools"
    val costNote = if (totalCostCents > 0) "${totalCostCents}¢" else "no priced AIGC"
    val narrative = "Session ${sid.value}: $turnCount turn(s), " +
        "${totalTokensIn + totalTokensOut} tokens, $costNote, $toolNote. " +
        "Last model: ${lastModelId ?: "n/a"}."

    return ToolResult(
        title = "session_query recap ${sid.value}",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_RECAP,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
