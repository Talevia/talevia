package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.tool.RegisteredTool
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * `select=tool_spec_budget` — single-row snapshot of how many tokens the
 * registered tool specs cost the LLM on every turn (VISION §5.4 +
 * §3a-10). Registry-wide, session-independent — passing sessionId is
 * rejected so typos surface. [registryResolved] is false only when the
 * session-query is wired without a registry (non-Agent rigs), in which
 * case every count reports zero.
 */
@Serializable data class ToolSpecBudgetRow(
    val toolCount: Int,
    /** Sum across all tools of `(id + helpText + JsonSchema).length / 4`. Order-of-magnitude estimate. */
    val estimatedTokens: Int,
    /** Sum of raw spec byte lengths — useful when the caller wants to apply its own tokenizer ratio. */
    val specBytes: Int,
    /** True when a registry was injected; false on test rigs that skip registry wiring. */
    val registryResolved: Boolean,
    /** Top [ToolSpecBudgetEntry]s by [ToolSpecBudgetEntry.estimatedTokens] descending. Capped so the response stays cheap. */
    val topByTokens: List<ToolSpecBudgetEntry> = emptyList(),
)

@Serializable data class ToolSpecBudgetEntry(
    val toolId: String,
    /** `(id + helpText + schema).length / 4`, rounded half-up. */
    val estimatedTokens: Int,
    val specBytes: Int,
)

/**
 * `select=tool_spec_budget` — registry-wide snapshot of the tool-spec
 * token overhead the LLM pays on every turn. Answers "how many tokens am
 * I burning just to describe my tools?" — the §3a-10 silent-cost
 * question. Unlike the other session_query selects this one is
 * session-independent (same answer for every session in this runtime);
 * `sessionId` is rejected.
 *
 * Cost model: for each registered tool we sum the UTF-16 char length of
 * `(id + helpText + JsonConfig.default.encodeToString(inputSchema))` and
 * divide by 4 — the standard "1 token ≈ 4 bytes" approximation
 * (matches the rest of our `TokenEstimator` heuristics, consistent with
 * OpenAI's cl100k and Anthropic tokenizer rules of thumb for English +
 * JSON payloads). The numbers are order-of-magnitude, not exact —
 * a provider-specific tokenizer would give a tighter fit but the
 * tool-spec shape is stable enough that the approximation is
 * actionable ("my registry costs 15k tokens per turn" vs "18k" — both
 * large; time to consolidate).
 *
 * The breakdown is capped at [TOP_N] entries sorted by estimatedTokens
 * descending so the LLM can reason about "what's hogging the budget"
 * without paying for a 105-row table on every call.
 */
private const val TOP_N: Int = 5

/**
 * Divide by 4 with half-up rounding to keep small-but-nonzero tool
 * specs visible as ≥ 1 token rather than silently rounding to zero.
 */
private fun bytesToTokens(bytes: Int): Int = (bytes + 2) / 4

/**
 * Compute the registry-wide tool-spec budget snapshot. Shared by the
 * `select=tool_spec_budget` query and by the runtime warning monitor in
 * [io.talevia.core.agent.ToolSpecBudgetMonitor], so both lanes use the
 * same `(id + helpText + schema).length / 4` heuristic and any future
 * tokenizer swap lands in one place.
 */
internal fun computeToolSpecBudget(toolRegistry: ToolRegistry?): ToolSpecBudgetRow {
    val tools: List<RegisteredTool> = toolRegistry?.all().orEmpty()
    val entries = tools.map { rt ->
        val schemaJson = JsonConfig.default.encodeToString(JsonElement.serializer(), rt.spec.inputSchema)
        val bytes = rt.id.length + rt.helpText.length + schemaJson.length
        ToolSpecBudgetEntry(
            toolId = rt.id,
            estimatedTokens = bytesToTokens(bytes),
            specBytes = bytes,
        )
    }
    val totalBytes = entries.sumOf { it.specBytes }
    val totalTokens = entries.sumOf { it.estimatedTokens }
    val top = entries.sortedByDescending { it.estimatedTokens }.take(TOP_N)
    return ToolSpecBudgetRow(
        toolCount = tools.size,
        estimatedTokens = totalTokens,
        specBytes = totalBytes,
        registryResolved = toolRegistry != null,
        topByTokens = top,
    )
}

internal fun runToolSpecBudgetQuery(
    toolRegistry: ToolRegistry?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    require(input.sessionId == null) {
        "select='${SessionQueryTool.SELECT_TOOL_SPEC_BUDGET}' is a registry-wide snapshot; " +
            "sessionId does not apply."
    }
    val row = computeToolSpecBudget(toolRegistry)
    val tools: List<RegisteredTool> = toolRegistry?.all().orEmpty()
    val totalBytes = row.specBytes
    val totalTokens = row.estimatedTokens
    val top = row.topByTokens
    val rows: JsonArray = JsonConfig.default.encodeToJsonElement(
        ListSerializer(ToolSpecBudgetRow.serializer()),
        listOf(row),
    ).jsonArray
    val pretty = if (toolRegistry == null) {
        "tool-spec budget: registry not wired — showing zero totals."
    } else {
        val topPreview = top.joinToString(", ") { "${it.toolId}=${it.estimatedTokens}t" }
        "tool-spec budget: ${tools.size} tools ≈ $totalTokens tokens/turn (≈ $totalBytes bytes). " +
            "Top: $topPreview"
    }
    return ToolResult(
        title = "session_query: tool_spec_budget",
        outputForLlm = pretty,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_TOOL_SPEC_BUDGET,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
