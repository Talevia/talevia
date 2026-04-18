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

    fun specs(): List<ToolSpec> = tools.values.map { it.spec }
}

class RegisteredTool internal constructor(private val tool: Tool<*, *>) {
    val id: String get() = tool.id
    val description: String get() = tool.description
    val permission get() = tool.permission

    val spec: ToolSpec by lazy { ToolSpec(tool.id, tool.description, tool.inputSchema) }

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
