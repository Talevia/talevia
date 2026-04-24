package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=spend_summary` — M2 criterion 4 "成本可见" view. Answers
 * **"how much has this session spent on AIGC, broken down by provider?"**
 *
 * Complements [SpendSummaryRow] / `select=spend` (which groups by `toolId`)
 * with a provider-grouped roll-up: two different lenses on the same
 * lockfile-filtered-by-session data. The per-provider breakdown matches
 * the milestone criterion's wording "至少按 provider 分档"; it is what the
 * user needs to answer "am I spending more on OpenAI than Replicate on
 * this edit?" without having to memorise which tool routes to which
 * provider.
 *
 * **Fields** (all nullable-when-unknown per §3a #4 — a binary `hasCost`
 * flag would drop the "this provider's cost is unknown" nuance):
 * - [SessionSpendByProviderRow.totalCalls] — count of lockfile entries
 *   stamped with this session id. Includes entries whose pricing rule
 *   didn't match (`costCents = null`); the "how many AIGC calls did this
 *   session fire" answer shouldn't depend on whether `AigcPricing` knew
 *   the model yet.
 * - [SessionSpendByProviderRow.totalTokens] — sum of provider-reported
 *   token counts, when the entry has them. Currently always `null`:
 *   `LockfileEntry` does not yet record per-entry token usage (the
 *   provider's `UsageEvent` is emitted on the bus but not stamped onto
 *   the lockfile). Intentionally nullable-not-zero so a future plumb-
 *   through (follow-up P2 bullet) can fill real numbers without the row
 *   shape changing — a reader that saw `0` today could not distinguish
 *   "really zero" from "not yet plumbed".
 * - [SessionSpendByProviderRow.estimatedUsdCents] — `costCents` sum
 *   converted to a `Double` for readability (dollars = cents / 100). Null
 *   when every entry in the bucket has `costCents = null` (unknown-
 *   pricing providers); caller gets "X calls, no pricing rules" cleanly
 *   separated from "X calls, $0.00".
 * - [SessionSpendByProviderRow.perProviderBreakdown] — one entry per
 *   `provenance.providerId` seen. Sorted by providerId for stable diffs.
 *   Entries with `costCents != null` contribute to the total; entries
 *   without roll into the same provider's row but leave `usdCents`
 *   null-or-partial (see below).
 *
 * **Per-provider row accounting** — a provider bucket's `usdCents` is
 * null only when **every** entry in that bucket has `costCents = null`.
 * Mixed buckets sum the known subset and still report the partial total
 * (matches existing `SpendSummaryRow.totalCostCents` convention). The
 * `unknownCalls` field counts how many of the bucket's calls had no
 * pricing rule, so the reader can distinguish "\$0.40 known across 4
 * calls" from "\$0.40 known across 4 calls + 2 unpriced".
 */
@Serializable data class SessionSpendSummaryRow(
    val sessionId: String,
    val projectId: String,
    val totalCalls: Int,
    val totalTokens: Int? = null,
    val estimatedUsdCents: Double? = null,
    val unknownCostCalls: Int = 0,
    val perProviderBreakdown: List<SessionSpendByProviderRow> = emptyList(),
    val projectResolved: Boolean = true,
)

@Serializable data class SessionSpendByProviderRow(
    val providerId: String,
    val calls: Int,
    val tokens: Int? = null,
    val usdCents: Double? = null,
    val unknownCalls: Int = 0,
)

/**
 * `select=spend_summary` — per-provider roll-up of one session's AIGC
 * spend. Walks the session's bound project's lockfile, filters entries
 * whose stamped `sessionId` matches, groups by `provenance.providerId`,
 * and converts integer `costCents` into `Double` USD cents for
 * readability.
 *
 * Shares the same scope limitations as [runSpendQuery] (cross-project
 * sessions see only the current project; project-not-found returns an
 * empty zero-row rather than erroring).
 */
internal suspend fun runSpendSummaryQuery(
    sessions: SessionStore,
    projects: ProjectStore?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_SPEND_SUMMARY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val project = projects?.get(session.projectId)
    val entries: List<LockfileEntry> =
        project?.lockfile?.entries?.filter { it.sessionId == sid.value } ?: emptyList()

    // Aggregate per-provider. Bucket state tracks (calls, sumKnownCents,
    // unknownCalls) — aggregate totals fall out by summing the bucket
    // state.
    data class Bucket(
        var calls: Int = 0,
        var sumKnownCents: Long = 0L,
        var knownCalls: Int = 0,
        var unknownCalls: Int = 0,
    )

    val buckets = linkedMapOf<String, Bucket>()
    var totalKnownCents = 0L
    var totalKnownCalls = 0
    var totalUnknownCalls = 0

    for (entry in entries) {
        val providerId = entry.provenance.providerId
        val bucket = buckets.getOrPut(providerId) { Bucket() }
        bucket.calls += 1
        val cents = entry.costCents
        if (cents == null) {
            bucket.unknownCalls += 1
            totalUnknownCalls += 1
        } else {
            bucket.sumKnownCents += cents
            bucket.knownCalls += 1
            totalKnownCents += cents
            totalKnownCalls += 1
        }
    }

    val breakdown = buckets.entries
        .sortedBy { it.key }
        .map { (providerId, b) ->
            SessionSpendByProviderRow(
                providerId = providerId,
                calls = b.calls,
                tokens = null, // LockfileEntry has no token fields yet — see KDoc above.
                usdCents = if (b.knownCalls == 0) null else b.sumKnownCents.toDouble(),
                unknownCalls = b.unknownCalls,
            )
        }

    val row = SessionSpendSummaryRow(
        sessionId = session.id.value,
        projectId = session.projectId.value,
        totalCalls = entries.size,
        totalTokens = null,
        estimatedUsdCents = if (totalKnownCalls == 0) null else totalKnownCents.toDouble(),
        unknownCostCalls = totalUnknownCalls,
        perProviderBreakdown = breakdown,
        projectResolved = project != null,
    )
    val rows = encodeRows(
        ListSerializer(SessionSpendSummaryRow.serializer()),
        listOf(row),
    )

    val dollars = (totalKnownCents / 100.0).toString().take(10)
    val providersTail = when (breakdown.size) {
        0 -> ""
        1 -> " on ${breakdown[0].providerId}"
        else -> " across ${breakdown.size} provider(s)"
    }
    val unknownTail =
        if (totalUnknownCalls == 0) ""
        else " (+$totalUnknownCalls unpriced call${if (totalUnknownCalls == 1) "" else "s"})"
    val projectTail = if (project == null) " [project ${session.projectId.value} not found]" else ""
    val summary =
        "Session ${session.id.value}: ${entries.size} AIGC call(s)$providersTail, " +
            "~\$$dollars known$unknownTail$projectTail."

    return ToolResult(
        title = "session_query spend_summary ${session.id.value} (${entries.size} call(s))",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_SPEND_SUMMARY,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
