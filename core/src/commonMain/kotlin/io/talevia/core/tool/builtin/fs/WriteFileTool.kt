package io.talevia.core.tool.builtin.fs

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.FileSystem
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Write UTF-8 text to a file on the user's external filesystem, truncating any
 * existing content. Behind `fs.write` permission (default ASK) with `path` as
 * the matchable pattern. For appending (without overwrite), save/read/rewrite
 * or extend this tool with an `append` flag; v1 only supports overwrite to
 * keep semantics trivial.
 */
class WriteFileTool(private val fs: FileSystem) : Tool<WriteFileTool.Input, WriteFileTool.Output> {
    @Serializable
    data class Input(
        val path: String,
        val content: String,
        val createDirs: Boolean = false,
    )

    @Serializable
    data class Output(
        val path: String,
        val bytesWritten: Long,
    )

    override val id: String = "write_file"
    override val helpText: String =
        "Write UTF-8 text to an absolute path (overwrites existing file). Set createDirs=true " +
            "to mkdir -p the parent. Never write to Project JSON / ~/.talevia/ / media catalog; " +
            "use the typed Project tools for those."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "fs.write",
        patternFrom = { raw -> extractPathPattern(raw, field = "path") },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the file to write.")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "UTF-8 text to write. Fully replaces existing content.")
            }
            putJsonObject("createDirs") {
                put("type", "boolean")
                put("description", "If true, create missing parent directories (mkdir -p).")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("content"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val bytes = fs.writeText(input.path, input.content, createDirs = input.createDirs)
        return ToolResult(
            title = "write_file ${input.path}",
            outputForLlm = "wrote $bytes bytes to ${input.path}",
            data = Output(path = input.path, bytesWritten = bytes),
        )
    }
}
