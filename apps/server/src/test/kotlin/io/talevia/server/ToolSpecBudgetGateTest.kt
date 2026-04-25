package io.talevia.server

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.SessionQueryTool
import io.talevia.core.tool.builtin.session.query.ToolSpecBudgetRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ratchet gate for the tool-spec token surface the LLM pays every turn
 * (VISION §5.7 / §3a-10). Fails if the registered-tool spec budget
 * exceeds [CEILING_TOKENS]; the ceiling is a conservative
 * current-state-plus-headroom snapshot so further growth is blocked
 * while the `debt-shrink-tool-spec-surface` effort lowers the bar in
 * follow-up cycles.
 *
 * How it measures: builds a full `ServerContainer(rawEnv = emptyMap())`
 * — same registry every production server boot gets, but without any
 * provider keys (it's a spec-size question, not a call-the-provider
 * question) — and runs the existing
 * `session_query(select=tool_spec_budget)` path. That query's own
 * implementation is the canonical cost model:
 *   `(id + helpText + JsonConfig.default.encodeToString(inputSchema)).length / 4`
 * summed across every registered tool. Matching the runtime computation
 * means this gate reflects the same number a live `session_query` would
 * surface, not a separate source-walker approximation that could drift.
 *
 * Tightening the ceiling: after any `debt-consolidate-*` / tool-delete
 * cycle lowers the budget, edit [CEILING_TOKENS] downward. The target
 * per the backlog bullet is `<= 15000` once shrink lands, then
 * `<= 10000`. Do NOT loosen — a regression here is a bullet for the
 * next cycle, not a bumped ceiling.
 *
 * The test intentionally lives in `:apps:server:test` rather than
 * `:core:jvmTest`: only an AppContainer knows which tools the
 * production runtime registers. `ServerContainer` is the
 * cheapest-to-construct canonical registry (no provider HTTP, no file
 * picker, no Compose). CLI / Desktop / Android / iOS registries differ
 * only at the margins (engine-gated AIGC tools); the server's number
 * is a reasonable proxy for the per-turn cost across platforms.
 */
class ToolSpecBudgetGateTest {

    /**
     * Current ceiling — a don't-regress baseline above today's measured
     * budget (25_253 tokens across 97 tools when this gate landed).
     * Small headroom (~7%) accepts incremental growth from adding one
     * small tool without a cycle, but fails the gate on any meaningful
     * spec addition so the bloat isn't silent.
     *
     * Ratchet plan (updated 2026-04-23 after the helpText-trim cycle
     * via `debt-shrink-tool-spec-surface-to-22500`; see
     * `docs/decisions/2026-04-23-debt-shrink-tool-spec-surface-to-22500.md`):
     *   - 27_000 (pre-ratchet, cycle 6): 25_253 baseline + ~7% buffer.
     *   - 25_000 (previous cycle): 24_384 measured + ~2.5% buffer,
     *     after five consolidations landed.
     *   - 22_500 (cycle-31): 22_470 measured + ~0.1% buffer, after
     *     trimming helpText on top-10 offenders (project_query /
     *     session_query / source_query / draft_plan / fork_project /
     *     filter_action / transition_action / track_action /
     *     create_project_from_template / import_media /
     *     project_maintenance_action / update_source_node_body /
     *     todowrite). -1_911 tokens / -7.8%.
     *   - 22_600 (cycle-39): 22_518 measured. +18 tokens above prior
     *     ceiling; load-bearing addition of
     *     `provider_query(select=cost_compare)` per
     *     `docs/decisions/2026-04-23-core-provider-cost-compare-query.md`.
     *     Cost-compare is a new LLM-facing select that answers
     *     "cheapest model for this request?" — high-value surface no
     *     consolidation path shrinks further. ~0.4% buffer.
     *   - 22_700 (cycle-51): 22_623 measured. +23 tokens above prior
     *     ceiling; load-bearing addition of `source_query(select=nodes)`
     *     `hasParent` filter. hasParent is the DAG-position filter that
     *     turns `dag_summary.rootNodeIds` from a one-off snapshot into a
     *     first-class `nodes` filter; no consolidation path shrinks
     *     this further. ~0.3% buffer.
     *   - 23_000 (cycle-74): 22_935 measured. +235 tokens above prior
     *     ceiling; load-bearing addition of
     *     `provider_query(select=aigc_cost_estimate)` per backlog
     *     `aigc-cost-estimate-tool`. Bridges AigcPricing.estimateCents
     *     to LLM plan-time so the agent doesn't compute "8s × $0.30"
     *     by hand from the priceBasis string. helpText + per-field
     *     descriptions trimmed before bumping. ~0.3% buffer.
     *   - 23_100 (cycle-77): 23_045 measured. +45 tokens above prior
     *     ceiling; load-bearing addition of
     *     `project_query(select=source_binding_stats)`. Per-kind coverage
     *     answers "how many character_refs are unused?" in one query
     *     instead of an O(n) walk through `consistency_propagation`.
     *     ~0.2% buffer.
     *   - 23_200 (cycle-83): 23_124 measured. +24 tokens above prior
     *     ceiling; load-bearing additions of
     *     `session_query(select=preflight_summary)` (cycle-82) +
     *     `session_query(select=step_history)` (cycle-83). preflight
     *     consolidates 4 query lanes into one row to cut per-plan tool
     *     calls; step_history exposes per-step timeline previously only
     *     reconstructable from the parts select. ~0.3% buffer.
     *   - 22_500 (cycle-84): 22_492 measured — REACHED cycle-31 P0
     *     target. Trim recipe replicated against this cycle's top-3
     *     offenders (project_query 1481→1269, session_query 1344→1071,
     *     clip_action 1324→1177). Saved 632 tokens / 2.7%; ~0.04%
     *     buffer (8 tokens).
     *   - 22_600 (this cycle, cycle-91): 22_543 measured. +43 over
     *     prior 22_500 ceiling; load-bearing addition of
     *     `provider_query(select=rate_limit_history)` per backlog
     *     `provider-query-rate-limit-history`. Surfaces per-provider
     *     429 retry counts from a new bus aggregator
     *     (RateLimitHistoryRecorder) — ops dashboards / cost-aware
     *     operators see "I'm hitting Anthropic's tier-1 cap N times
     *     today" without tailing the bus. helpText already trimmed
     *     to a one-line schema-only signature; remaining cost is the
     *     row's 6 field names which are the answer. ~0.25% buffer.
     *   - 20_000 (next target, R.6 P0 threshold): requires either
     *     deleting ~10 more tools or moving per-action details to a
     *     `list_tools(select=tool_detail)` sidecar so the live spec
     *     shrinks further without losing the narrative.
     *   - 15_000 / 10_000: later ratchet steps, same pattern.
     *
     * Tightening is the only legal direction. A regression that would
     * push the budget over ceiling is a backlog bullet for the next
     * cycle, NOT a bumped ceiling. If the growth is truly load-bearing
     * (new high-value tool with no consolidation path), bump
     * CEILING_TOKENS in this test and explain in the commit body why
     * the number increased — rationale lives in the commit body since
     * docs/decisions/ was removed (commit ae213b05).
     */
    private val CEILING_TOKENS: Int = 22_600

    @Test
    fun registeredToolSpecsFitWithinCeiling() {
        val row = measureBudget()

        val estimated = row.estimatedTokens
        val toolCount = row.toolCount
        val headroom = max(0, CEILING_TOKENS - estimated)
        val message = buildString {
            append("tool_spec_budget = $estimated tokens across $toolCount tools ")
            append("(ceiling=$CEILING_TOKENS, headroom=$headroom).")
            if (estimated > CEILING_TOKENS) {
                append("\n\nGate tripped. Either consolidate near-duplicate tools per ")
                append("`debt-shrink-tool-spec-surface`, or — if the growth is truly load-bearing ")
                append("— update CEILING_TOKENS in this test + add a matching decision file in ")
                append("docs/decisions/. Do NOT silently raise the ceiling without a decision ")
                append("explaining what justifies the new number.\n")
                append("Biggest offenders (top ${row.topByTokens.size}):\n")
                row.topByTokens.forEach { entry ->
                    append("  - ${entry.toolId} ~ ${entry.estimatedTokens} tokens\n")
                }
            }
        }
        assertTrue(estimated <= CEILING_TOKENS, message)
    }

    /**
     * Positive control — the measurement path itself must produce a
     * non-trivial number. A future refactor that accidentally returns 0
     * (e.g. `registry.all()` swapped for an empty list) would silently
     * pass the ceiling check; this test catches that regression.
     */
    @Test
    fun budgetIsNonTrivial() {
        val row = measureBudget()
        assertTrue(row.toolCount > 50, "expected > 50 registered tools; got ${row.toolCount}")
        assertTrue(
            row.estimatedTokens > 5_000,
            "tool_spec_budget suspiciously low (${row.estimatedTokens} tokens). A zero-spec regression " +
                "would silently pass the ceiling check — this positive control guards against that.",
        )
    }

    /**
     * Dispatch `session_query(select=tool_spec_budget)` through the
     * registered tool surface so the gate sees the same number a live
     * session would. Using `tools["session_query"]!!.dispatch(...)`
     * (rather than reaching the `internal` handler directly) keeps the
     * test on the public API and is resilient to the handler moving
     * between packages.
     */
    private fun measureBudget(): ToolSpecBudgetRow {
        val container = ServerContainer(rawEnv = emptyMap())
        val inputJson: JsonObject = buildJsonObject {
            put("select", SessionQueryTool.SELECT_TOOL_SPEC_BUDGET)
        }
        val ctx = ToolContext(
            sessionId = SessionId("budget-gate"),
            messageId = MessageId("budget-gate"),
            callId = CallId("budget-gate"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        val result = runBlocking { container.tools["session_query"]!!.dispatch(inputJson, ctx) }
        val data = result.data as SessionQueryTool.Output
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ToolSpecBudgetRow.serializer()),
            data.rows,
        )
        require(rows.size == 1) { "tool_spec_budget query returned ${rows.size} rows — expected exactly 1" }
        val row = rows.single()
        require(row.registryResolved) {
            "registry did not resolve in ServerContainer; test harness wiring broke"
        }
        return row
    }
}
