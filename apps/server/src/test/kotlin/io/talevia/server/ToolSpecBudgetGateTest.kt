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
     * Ratchet plan (updated 2026-04-23 after cycle-23 — five
     * `debt-consolidate-*` bullets landed transitions / filter /
     * snapshot / maintenance / session; see
     * `docs/decisions/2026-04-23-debt-tool-spec-budget-ratchet-step-20k.md`
     * for the partial-progress rationale):
     *   - 27_000 (pre-ratchet, cycle 6): 25_253 baseline + ~7% buffer.
     *   - 25_000 (this cycle): 24_384 measured + ~2.5% buffer. The
     *     five consolidations dropped tool count 97 → 88 (-9%) but
     *     budget only 25_253 → 24_384 (-3.4%) because action-dispatched
     *     helpTexts are longer than any individual tool's. 20_000 not
     *     reachable without deeper surface reduction.
     *   - 20_000 (next target, R.6 P0 threshold): requires either
     *     deleting ~10 more tools or trimming helpText across the
     *     broad surface. Open as `debt-shrink-tool-spec-surface`
     *     follow-up.
     *   - 15_000 / 10_000: later ratchet steps, same pattern.
     *
     * Tightening is the only legal direction. A regression that would
     * push the budget over ceiling is a backlog bullet for the next
     * cycle, NOT a bumped ceiling. If the growth is truly load-bearing
     * (new high-value tool with no consolidation path), add a matching
     * decision file in `docs/decisions/` alongside the ceiling bump
     * explaining why this number increased.
     */
    private val CEILING_TOKENS: Int = 25_000

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
