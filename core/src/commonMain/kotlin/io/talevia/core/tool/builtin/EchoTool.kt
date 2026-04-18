package io.talevia.core.tool.builtin

import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Trivial tool used by the Agent loop tests and as a smoke check for tool dispatch.
 * Returns the input string unchanged. Permission is "echo" (allow by default).
 */
class EchoTool : Tool<EchoTool.Input, EchoTool.Output> {
    @Serializable data class Input(val text: String)
    @Serializable data class Output(val echoed: String)

    override val id: String = "echo"
    override val description: String = "Echoes the provided text back unchanged. Used for smoke-testing tool dispatch."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("echo")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "The text to echo back.")
            }
        }
        put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("text"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> =
        ToolResult(
            title = "echo",
            outputForLlm = input.text,
            data = Output(input.text),
        )

    companion object {
        /** Default permission rule used by tests / dev configs. */
        val ALLOW_RULE = io.talevia.core.permission.PermissionRule(
            permission = "echo",
            pattern = "*",
            action = PermissionAction.ALLOW,
        )
    }
}
