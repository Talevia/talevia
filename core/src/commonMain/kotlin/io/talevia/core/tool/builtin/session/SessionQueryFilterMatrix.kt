package io.talevia.core.tool.builtin.session

import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_ACTIVE_RUN_SUMMARY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_ANCESTORS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_CACHE_STATS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_CANCELLATION_HISTORY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_COMPACTIONS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_CONTEXT_PRESSURE
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_FALLBACK_HISTORY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_FORKS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_MESSAGE
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_MESSAGES
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_PARTS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_PERMISSION_HISTORY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_PERMISSION_RULES
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_PREFLIGHT_SUMMARY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_RECAP
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_RUN_FAILURE
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_RUN_STATE_HISTORY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_SESSIONS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_SESSION_METADATA
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_SPEND
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_SPEND_SUMMARY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_STATUS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_STEP_HISTORY
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_TOOL_CALLS
import io.talevia.core.tool.builtin.session.SessionQueryTool.Companion.SELECT_TOOL_SPEC_BUDGET

/**
 * Per-select declaration of which [SessionQueryTool.Input] filter
 * fields the select accepts. Drives the generic
 * `rejectIncompatibleFilters` pass — a filter that's set by the
 * caller but absent from the select's accepted set is a typo / wrong
 * select, and we fail loud naming both the offending field and the
 * selects that DO accept it.
 *
 * Replaces the cycle-30s era ad-hoc if-else chain in
 * `SessionQueryTool.rejectIncompatibleFilters` (cycle-94 §3a #12
 * trigger fired at select count ≥ 20). Now: each new select adds one
 * entry to this map; rejection logic stays in one declarative
 * walker.
 *
 * **`sessionId` semantics:** present in the accepted set when the
 * select operates on a session; absent for `select=sessions`
 * (enumerate-all) and `select=tool_spec_budget` (registry-wide).
 * "Required-but-missing" still surfaces from each select's handler
 * via its own error — the matrix only covers "wrong field for this
 * select".
 */
internal val SESSION_QUERY_ACCEPTED_FIELDS: Map<String, Set<String>> = mapOf(
    SELECT_SESSIONS to setOf("projectId", "includeArchived"),
    SELECT_MESSAGES to setOf("sessionId", "role"),
    SELECT_PARTS to setOf("sessionId", "kind", "includeCompacted"),
    SELECT_FORKS to setOf("sessionId"),
    SELECT_ANCESTORS to setOf("sessionId"),
    SELECT_TOOL_CALLS to setOf("sessionId", "toolId", "includeCompacted"),
    SELECT_COMPACTIONS to setOf("sessionId"),
    SELECT_STATUS to setOf("sessionId"),
    SELECT_SESSION_METADATA to setOf("sessionId"),
    SELECT_MESSAGE to setOf("messageId"),
    SELECT_SPEND to setOf("sessionId"),
    SELECT_SPEND_SUMMARY to setOf("sessionId"),
    SELECT_CACHE_STATS to setOf("sessionId"),
    SELECT_CONTEXT_PRESSURE to setOf("sessionId"),
    SELECT_RUN_STATE_HISTORY to setOf("sessionId", "sinceEpochMs"),
    SELECT_TOOL_SPEC_BUDGET to emptySet(),
    SELECT_RUN_FAILURE to setOf("sessionId", "messageId"),
    SELECT_FALLBACK_HISTORY to setOf("sessionId", "messageId"),
    SELECT_CANCELLATION_HISTORY to setOf("sessionId", "messageId"),
    SELECT_PERMISSION_HISTORY to setOf("sessionId"),
    SELECT_PERMISSION_RULES to setOf("sessionId"),
    SELECT_PREFLIGHT_SUMMARY to setOf("sessionId"),
    SELECT_RECAP to setOf("sessionId"),
    SELECT_STEP_HISTORY to setOf("sessionId", "messageId"),
    SELECT_ACTIVE_RUN_SUMMARY to setOf("sessionId"),
)

/**
 * Generic per-input filter walker — for each filter field that the
 * caller set, check if it's in this select's accepted set; otherwise
 * collect it as misapplied. Reports all misapplied fields in one
 * error so the caller fixes them in one round-trip rather than
 * one-error-at-a-time.
 *
 * Each filter that's set is mapped to its name; the walker checks
 * presence-in-accepted-set against [SESSION_QUERY_ACCEPTED_FIELDS].
 * Names are stringly-typed because the matrix is a Map<String,
 * Set<String>>; this loses some compile-time safety but keeps the
 * matrix declarative (and the test layer guards against typos via
 * `everySessionQuerySelectHasFilterMatrixEntry`).
 */
internal fun rejectIncompatibleSessionQueryFilters(
    select: String,
    input: SessionQueryTool.Input,
) {
    val accepted = SESSION_QUERY_ACCEPTED_FIELDS[select] ?: return
    val present = buildList {
        if (input.sessionId != null) add("sessionId")
        if (input.projectId != null) add("projectId")
        if (input.includeArchived != null) add("includeArchived")
        if (input.role != null) add("role")
        if (input.kind != null) add("kind")
        if (input.includeCompacted != null) add("includeCompacted")
        if (input.toolId != null) add("toolId")
        if (input.messageId != null) add("messageId")
        if (input.sinceEpochMs != null) add("sinceEpochMs")
    }
    val misapplied = present.filterNot { it in accepted }
    if (misapplied.isEmpty()) return
    val withAcceptors = misapplied.joinToString(", ") { fieldName ->
        val acceptors = SESSION_QUERY_ACCEPTED_FIELDS.entries
            .filter { fieldName in it.value }
            .map { it.key }
        if (acceptors.isEmpty()) {
            "$fieldName (no select accepts this — bug in matrix?)"
        } else {
            "$fieldName (select=${acceptors.joinToString("|")} only)"
        }
    }
    error("The following filter fields do not apply to select='$select': $withAcceptors")
}
