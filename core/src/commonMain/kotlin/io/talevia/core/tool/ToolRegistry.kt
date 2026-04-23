package io.talevia.core.tool

import io.talevia.core.JsonConfig
import kotlinx.serialization.json.JsonElement

/**
 * Holds registered Tools and exposes both their LLM-facing specs and a non-generic
 * dispatch entry point. Star-projected `Tool<*, *>` storage is unavoidable; the
 * dispatcher casts in one tightly scoped place.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, RegisteredTool>()

    fun register(tool: Tool<*, *>) {
        tools[tool.id] = RegisteredTool(tool)
    }

    fun unregister(id: String) {
        tools.remove(id)
    }

    operator fun get(id: String): RegisteredTool? = tools[id]

    fun all(): List<RegisteredTool> = tools.values.toList()

    /**
     * Unfiltered spec bundle — every registered tool. Kept for back-compat with callers
     * that don't have (or don't care about) session state; [specs] with a
     * [ToolAvailabilityContext] is the preferred entry point for the agent loop.
     */
    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    /**
     * Context-filtered spec bundle. Drops tools whose [Tool.applicability] is
     * unavailable under [ctx], **then** drops anything the caller listed in
     * [ToolAvailabilityContext.disabledToolIds] (session-scoped "stop using
     * generate_video" controls). Order is preserved.
     *
     * AgentTurnExecutor calls this per turn so an unbound session doesn't
     * ship ~dozens of project-scoped tool schemas to the provider, and a
     * session that disabled some tools never exposes them to the model.
     */
    fun specs(ctx: ToolAvailabilityContext): List<ToolSpec> =
        tools.values.asSequence()
            .filter { it.applicability.isAvailable(ctx) }
            .filter { it.id !in ctx.disabledToolIds }
            .map { it.spec }
            .toList()
}

class RegisteredTool internal constructor(private val tool: Tool<*, *>) {
    val id: String get() = tool.id
    val helpText: String get() = tool.helpText
    val permission get() = tool.permission
    val applicability: ToolApplicability get() = tool.applicability

    val spec: ToolSpec by lazy { ToolSpec(tool.id, tool.helpText, tool.inputSchema) }

    suspend fun dispatch(rawInput: JsonElement, ctx: ToolContext): ToolResult<*> {
        @Suppress("UNCHECKED_CAST")
        val t = tool as Tool<Any, Any>
        val input = JsonConfig.default.decodeFromJsonElement(t.inputSerializer, rawInput)
        return t.execute(input, ctx)
    }

    fun encodeOutput(result: ToolResult<*>): JsonElement {
        @Suppress("UNCHECKED_CAST")
        val t = tool as Tool<Any, Any>
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Any
        return JsonConfig.default.encodeToJsonElement(t.outputSerializer, data)
    }
}
