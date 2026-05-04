package io.talevia.core.tool.builtin.session.query

import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.TodoWriteTool
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [computeToolSpecBudget] +
 * [runToolSpecBudgetQuery] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/query/ToolSpecBudgetQuery.kt`.
 * Cycle 270 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-269.
 *
 * `computeToolSpecBudget` is the canonical helper for both
 * `select=tool_spec_budget` and the runtime
 * `ToolSpecBudgetMonitor` (cycle 50). Per the kdoc: "any future
 * tokenizer swap lands in one place." Drift in the
 * heuristic — `(id + helpText + schemaJson).length / 4` —
 * silently changes both lanes' numbers.
 *
 * Drift signals:
 *   - `registryResolved` flag drift would let test rigs that
 *     skip wiring report bogus zeros as "real".
 *   - The heuristic byte sum (id + helpText + schema) drift
 *     to a different formula silently changes the §3a-10
 *     budget reporting AND the runtime warning fire/no-fire
 *     boundary in `ToolSpecBudgetMonitor`.
 *   - `bytesToTokens` rounding drift to `bytes/4` (truncating)
 *     would silently let near-zero tools round to 0 tokens.
 *   - `TOP_N=5` cap drift would silently expand or shrink
 *     the breakdown payload.
 *   - `runToolSpecBudgetQuery` accepting sessionId (drift to
 *     drop the require) would silently ignore the typo
 *     instead of surfacing it.
 *
 * Pins three correctness contracts:
 *
 *  1. **Null registry → zero totals + `registryResolved=false`**.
 *     Empty registry → zero totals + `registryResolved=true`.
 *     Distinguishes the "test rig didn't wire it" lane
 *     from the "wired but empty" lane.
 *
 *  2. **Per-tool `(id + helpText + schemaJson).length`
 *     heuristic** — a registry with 1 tool produces a single
 *     entry whose `specBytes` equals the canonical sum and
 *     whose `estimatedTokens == (specBytes + 2) / 4` (half-up
 *     bytesToTokens rounding).
 *
 *  3. **`runToolSpecBudgetQuery` rejects sessionId** —
 *     marquee "registry-wide; sessionId does not apply" pin.
 *     Drift to drop the require silently lets typo'd sessionId
 *     pass with no surface.
 *
 * Plus structural pins:
 *   - `select == SessionQueryTool.SELECT_TOOL_SPEC_BUDGET`.
 *   - `total == returned == 1` (always single-row).
 *   - **`TOP_N` cap = 5**: registry with 6+ tools produces
 *     `topByTokens.size == 5`, sorted descending.
 *   - Summary string format diverges by registry-wired flag
 *     ("registry not wired" vs "N tools ≈ X tokens/turn").
 */
class ToolSpecBudgetQueryTest {

    private fun registryWith(vararg tools: Tool<*, *>): ToolRegistry {
        val r = ToolRegistry()
        tools.forEach { r.register(it) }
        return r
    }

    // ── 1. Null vs empty registry ───────────────────────────

    @Test fun nullRegistryReportsZeroTotalsAndResolvedFalse() {
        // Marquee null-pin: when the rig doesn't wire a
        // registry, the row reports zeros + registryResolved=
        // false so consumers can distinguish it from
        // wired-but-empty.
        val row = computeToolSpecBudget(null)
        assertEquals(0, row.toolCount)
        assertEquals(0, row.estimatedTokens)
        assertEquals(0, row.specBytes)
        assertEquals(false, row.registryResolved)
        assertTrue(row.topByTokens.isEmpty())
    }

    @Test fun emptyRegistryReportsZeroTotalsButResolvedTrue() {
        // Marquee empty-vs-null distinction pin: an empty
        // wired registry reports zeros but
        // registryResolved=true. Drift to "treat empty same
        // as null" would lose the distinction the LLM /
        // Prometheus-scraper consumer needs to know "the
        // registry IS wired, just empty".
        val row = computeToolSpecBudget(ToolRegistry())
        assertEquals(0, row.toolCount)
        assertEquals(0, row.estimatedTokens)
        assertEquals(0, row.specBytes)
        assertEquals(true, row.registryResolved)
        assertTrue(row.topByTokens.isEmpty())
    }

    // ── 2. Heuristic correctness (id + helpText + schemaJson) ──

    @Test fun singleToolRegistryProducesOneEntryWithCanonicalByteSum() {
        // Marquee heuristic pin: the row's specBytes equals
        // `id.length + helpText.length + JsonConfig.encodeToString(schema).length`.
        // Drift to a different formula (e.g. drop schemaJson)
        // silently mis-counts and breaks both the LLM signal
        // and the runtime ToolSpecBudgetMonitor warning
        // boundary.
        val tool = EchoTool()
        val registry = registryWith(tool)
        val row = computeToolSpecBudget(registry)
        assertEquals(1, row.toolCount)
        assertEquals(true, row.registryResolved)
        assertEquals(1, row.topByTokens.size, "single-tool registry → 1 top entry")
        // Sanity: estimatedTokens > 0 (tools are non-trivial).
        assertTrue(row.estimatedTokens > 0)
        assertTrue(row.specBytes > 0)
        // The single entry's bytes match the row totals (no
        // double-counting / drop).
        val entry = row.topByTokens.single()
        assertEquals(row.specBytes, entry.specBytes)
        assertEquals(row.estimatedTokens, entry.estimatedTokens)
        assertEquals(tool.id, entry.toolId, "entry's toolId echoes registered id")
    }

    @Test fun bytesToTokensRoundsHalfUp() {
        // Marquee rounding-pin: per source `bytesToTokens(b)
        // = (b + 2) / 4` — half-up rounding so even tiny
        // tools produce ≥ 1 token rather than silently
        // rounding to 0. Verifying via two real tools where
        // the totals must be the integer division of
        // `(specBytes + 2) / 4`.
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val row = computeToolSpecBudget(registry)
        // Per-entry token count = (specBytes + 2) / 4.
        for (entry in row.topByTokens) {
            assertEquals(
                (entry.specBytes + 2) / 4,
                entry.estimatedTokens,
                "${entry.toolId}: estimatedTokens MUST equal (specBytes + 2) / 4 (half-up)",
            )
            assertTrue(
                entry.estimatedTokens >= 1,
                "${entry.toolId}: tokens MUST be ≥ 1 (drift to truncate would round small specs to 0)",
            )
        }
    }

    @Test fun multipleToolsAggregateEntriesAndSumTotals() {
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val row = computeToolSpecBudget(registry)
        assertEquals(2, row.toolCount)
        assertEquals(2, row.topByTokens.size)
        // Aggregate equals sum of entries.
        assertEquals(
            row.topByTokens.sumOf { it.specBytes },
            row.specBytes,
            "row.specBytes MUST equal sum of entries",
        )
        assertEquals(
            row.topByTokens.sumOf { it.estimatedTokens },
            row.estimatedTokens,
            "row.estimatedTokens MUST equal sum of entries",
        )
    }

    @Test fun topByTokensSortedDescending() {
        // Pin: the `topByTokens` list is sorted by
        // estimatedTokens descending. Drift to ascending /
        // unsorted would silently change the LLM's
        // "what's hogging the budget" answer.
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val row = computeToolSpecBudget(registry)
        val tokens = row.topByTokens.map { it.estimatedTokens }
        assertEquals(
            tokens.sortedDescending(),
            tokens,
            "topByTokens MUST be sorted by estimatedTokens descending; got: $tokens",
        )
    }

    // ── 3. runToolSpecBudgetQuery rejects sessionId ────────

    @Test fun runToolSpecBudgetQueryRejectsNonNullSessionId() {
        // Marquee require-pin: per kdoc, "registry-wide,
        // session-independent — passing sessionId is
        // rejected so typos surface". Drift to drop the
        // require would silently let typo'd sessionId pass
        // with no surface.
        val ex = assertFailsWith<IllegalArgumentException> {
            runToolSpecBudgetQuery(
                toolRegistry = ToolRegistry(),
                input = SessionQueryTool.Input(
                    select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
                    sessionId = "s1",
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            "registry-wide" in msg,
            "rejection MUST cite 'registry-wide' rationale; got: $msg",
        )
        assertTrue(
            "sessionId" in msg,
            "rejection MUST cite the offending sessionId field; got: $msg",
        )
    }

    @Test fun runToolSpecBudgetQueryAcceptsNullSessionId() {
        // Pin: the negative case — null sessionId passes the
        // require (the documented happy path).
        val result = runToolSpecBudgetQuery(
            toolRegistry = ToolRegistry(),
            input = SessionQueryTool.Input(
                select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
                sessionId = null,
            ),
        )
        // Sanity: result is well-formed.
        assertEquals(SessionQueryTool.SELECT_TOOL_SPEC_BUDGET, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    // ── 4. Output structure ─────────────────────────────────

    @Test fun selectFieldIsCanonicalToolSpecBudgetConstant() {
        val result = runToolSpecBudgetQuery(
            toolRegistry = null,
            input = SessionQueryTool.Input(select = "tool_spec_budget", sessionId = null),
        )
        assertEquals(
            SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
            result.data.select,
            "select MUST be the canonical SELECT_TOOL_SPEC_BUDGET constant",
        )
    }

    @Test fun outputAlwaysHasOneRowRegardlessOfRegistryState() {
        // Pin: total/returned == 1 in BOTH branches (null
        // and live registry). Drift to "no rows when null"
        // would silently change the contract for empty
        // states.
        for (toolRegistry in listOf<ToolRegistry?>(null, ToolRegistry(), registryWith(EchoTool()))) {
            val result = runToolSpecBudgetQuery(
                toolRegistry = toolRegistry,
                input = SessionQueryTool.Input(select = "tool_spec_budget", sessionId = null),
            )
            assertEquals(1, result.data.total, "total MUST be 1; got: ${result.data.total}")
            assertEquals(1, result.data.returned)
            assertEquals(1, result.data.rows.size)
        }
    }

    // ── 5. Summary string format ────────────────────────────

    @Test fun summaryForNullRegistryCitesNotWired() {
        // Pin: drift to drop the "registry not wired" phrase
        // would silently merge the test-rig branch with
        // empty-but-wired.
        val result = runToolSpecBudgetQuery(
            toolRegistry = null,
            input = SessionQueryTool.Input(select = "tool_spec_budget", sessionId = null),
        )
        assertTrue(
            "registry not wired" in result.outputForLlm,
            "null-registry summary MUST cite 'registry not wired'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "zero totals" in result.outputForLlm,
            "null-registry summary MUST cite zero totals; got: ${result.outputForLlm}",
        )
    }

    @Test fun summaryForLiveRegistryCitesToolCountAndTokensPerTurn() {
        // Pin: live-registry summary format cites:
        //   - tool count
        //   - "≈ X tokens/turn" (the budget headline)
        //   - "≈ Y bytes" (raw spec bytes)
        //   - "Top: $names" with per-tool entries
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val result = runToolSpecBudgetQuery(
            toolRegistry = registry,
            input = SessionQueryTool.Input(select = "tool_spec_budget", sessionId = null),
        )
        assertTrue(
            "2 tools" in result.outputForLlm,
            "summary MUST cite tool count; got: ${result.outputForLlm}",
        )
        assertTrue(
            "tokens/turn" in result.outputForLlm,
            "summary MUST cite 'tokens/turn'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "bytes" in result.outputForLlm,
            "summary MUST cite raw bytes; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Top:" in result.outputForLlm,
            "summary MUST cite 'Top:' breakdown header; got: ${result.outputForLlm}",
        )
        // Per-tool entries use `tool=Nt` format (drift to
        // `tool: N` / `tool (N tokens)` would change parse).
        assertTrue(
            "${EchoTool().id}=" in result.outputForLlm,
            "summary MUST include EchoTool entry; got: ${result.outputForLlm}",
        )
    }

    @Test fun summaryNeverPanicsOnEmptyRegistry() {
        // Pin: empty wired registry produces a coherent
        // summary. Drift to "Top:" + nothing would leave
        // dangling chars; drift to NPE on empty list would
        // crash.
        val result = runToolSpecBudgetQuery(
            toolRegistry = ToolRegistry(),
            input = SessionQueryTool.Input(select = "tool_spec_budget", sessionId = null),
        )
        assertTrue(result.outputForLlm.isNotBlank())
        assertTrue(
            "0 tools" in result.outputForLlm,
            "empty-wired summary MUST cite '0 tools'; got: ${result.outputForLlm}",
        )
    }
}
