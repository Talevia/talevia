package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=spend` — single-row AIGC cost aggregate attributed to one session.
 * Walks the session's bound project's lockfile and sums `costCents` for entries
 * whose stamped sessionId matches. Unknown-cost entries (no pricing rule) are
 * counted in [unknownCostEntries] and do not contribute to [totalCostCents].
 * [projectResolved] surfaces "the session's project no longer exists" — the
 * session record is still valid but spend is un-computable.
 */
@Serializable data class SpendSummaryRow(
    val sessionId: String,
    val projectId: String,
    val totalCostCents: Long,
    val entryCount: Int,
    val knownCostEntries: Int,
    val unknownCostEntries: Int,
    val byTool: Map<String, Long> = emptyMap(),
    val unknownByTool: Map<String, Int> = emptyMap(),
    val projectResolved: Boolean,
)

/**
 * `select=spend` — aggregate AIGC spend attributed to one session by walking
 * its bound project's lockfile and filtering entries whose stamped `sessionId`
 * matches. Each match contributes its `costCents` (null = unknown pricing,
 * counted in the unknown bucket, not rolled into the total).
 *
 * Scope limitation (documented): the query reads only the session's
 * `projectId` at call time. A session that switched projects mid-run (see
 * `switch_project`) will only have spend from its *current* project counted
 * here. Cross-project session spend is deferred to a later cycle — today's
 * flow (one session → one project for most videos) covers the common case.
 *
 * Silent bail on missing project: if the session's `projectId` no longer
 * resolves (project deleted), return an empty summary with zero counts
 * rather than erroring — the session record still exists and the user's
 * "how much did session X cost" question has a legitimate answer of "I
 * can't tell anymore".
 */
internal suspend fun runSpendQuery(
    sessions: SessionStore,
    projects: ProjectStore?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_SPEND}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val project = projects?.get(session.projectId)
    val entries = project?.lockfile?.entries?.filter { it.sessionId == sid.value } ?: emptyList()

    var totalCents = 0L
    var knownEntries = 0
    var unknownEntries = 0
    val byTool = mutableMapOf<String, Long>()
    val unknownByTool = mutableMapOf<String, Int>()

    for (entry in entries) {
        val cents = entry.costCents
        if (cents == null) {
            unknownEntries++
            unknownByTool[entry.toolId] = (unknownByTool[entry.toolId] ?: 0) + 1
            continue
        }
        knownEntries++
        totalCents += cents
        byTool[entry.toolId] = (byTool[entry.toolId] ?: 0L) + cents
    }

    val row = SpendSummaryRow(
        sessionId = session.id.value,
        projectId = session.projectId.value,
        totalCostCents = totalCents,
        entryCount = entries.size,
        knownCostEntries = knownEntries,
        unknownCostEntries = unknownEntries,
        byTool = byTool.sortedByKey(),
        unknownByTool = unknownByTool.sortedByKey(),
        projectResolved = project != null,
    )
    val rows = encodeRows(
        ListSerializer(SpendSummaryRow.serializer()),
        listOf(row),
    )
    val dollars = (totalCents / 100.0).toString().take(10)
    val unknownTail =
        if (unknownEntries == 0) ""
        else " (+$unknownEntries unknown-cost entr${if (unknownEntries == 1) "y" else "ies"})"
    val projectTail = if (project == null) " [project ${session.projectId.value} not found]" else ""
    val summary = "Session ${session.id.value} spent ~\$$dollars across " +
        "$knownEntries priced entr${if (knownEntries == 1) "y" else "ies"}$unknownTail$projectTail."
    return ToolResult(
        title = "session_query spend ${session.id.value} (${totalCents}¢)",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_SPEND,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun <V> Map<String, V>.sortedByKey(): Map<String, V> {
    if (isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, V>(size)
    for (k in keys.sorted()) out[k] = getValue(k)
    return out
}
