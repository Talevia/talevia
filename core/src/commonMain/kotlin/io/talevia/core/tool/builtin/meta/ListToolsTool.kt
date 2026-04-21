package io.talevia.core.tool.builtin.meta

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Enumerate the tools registered in the current runtime's
 * [ToolRegistry] — agent self-introspection.
 *
 * The LLM already receives every tool spec in its `LlmRequest.tools`
 * payload, but has no programmatic way to reason about tool
 * availability *inside* a turn. Concrete flows:
 *
 *  - The agent delegates to an internal plan: "I'll use
 *    `generate_image` for visuals and `synthesize_speech` for VO" —
 *    but in a fresh container only one of those may be registered
 *    (Replicate / OpenAI key set or not). Being able to assert
 *    "is `generate_image` available?" before committing to a plan
 *    avoids mid-task failure.
 *  - Debugging: the user asks "what tools does this build ship
 *    with?" and the agent can answer without guessing.
 *  - Capability discovery before subagent dispatch — once we have
 *    a subagent lane, it'll need the parent to enumerate tools to
 *    decide which ones to grant.
 *
 * Optional `prefix` filter — handy for scoping ("show me every
 * `generate_*` tool"). Returns each tool's id, helpText (the LLM-
 * facing description), and required permission keyword.
 *
 * Read-only, `tool.read` (new keyword, default ALLOW). Pure local
 * introspection — no external calls.
 */
class ListToolsTool(
    private val registry: ToolRegistry,
) : Tool<ListToolsTool.Input, ListToolsTool.Output> {

    @Serializable data class Input(
        /** Optional prefix match against tool id. Empty / null returns everything. */
        val prefix: String? = null,
        /** Cap on returned rows. Default 200, max 2000. */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
        val id: String,
        val helpText: String,
        val permission: String,
    )

    @Serializable data class Output(
        val total: Int,
        val returned: Int,
        val tools: List<Summary>,
    )

    override val id: String = "list_tools"
    override val helpText: String =
        "List tools registered in this runtime — agent self-introspection. Each row is the tool " +
            "id + help text + permission keyword. Use the optional `prefix` filter to scope " +
            "(\"generate_\", \"list_\", \"source.\"). Useful for capability checks before committing " +
            "to a plan in a container that may or may not have specific providers wired."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("tool.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prefix") {
                put("type", "string")
                put("description", "Optional prefix filter on tool id.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned rows (default 200, max 2000).")
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val needle = input.prefix?.takeIf { it.isNotBlank() }
        val allTools = registry.all().map {
            Summary(
                id = it.id,
                helpText = it.helpText,
                permission = it.permission.permission,
            )
        }
        val filtered = if (needle == null) allTools else allTools.filter { it.id.startsWith(needle) }
        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val capped = filtered.sortedBy { it.id }.take(cap)

        val scope = needle?.let { " prefix=$it" } ?: ""
        val summary = if (capped.isEmpty()) {
            "No tools registered$scope."
        } else {
            "${capped.size} of ${filtered.size} tool(s)$scope: " +
                capped.take(10).joinToString(", ") { it.id } +
                if (capped.size > 10) ", …" else ""
        }
        return ToolResult(
            title = "list tools (${capped.size})",
            outputForLlm = summary,
            data = Output(
                total = filtered.size,
                returned = capped.size,
                tools = capped,
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 2000
    }
}
