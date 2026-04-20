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
 * Read a UTF-8 text file from the user's external filesystem. Guards behind
 * `fs.read` permission (default ASK) with the `path` as the matchable pattern,
 * so selecting "Always" scopes the rule to exactly that file. Small file
 * contents (< [INLINE_THRESHOLD] bytes) go directly into `outputForLlm` so
 * the model can read them; larger files return a path + size summary and the
 * full content in `data`, steering the agent toward `glob` for discovery
 * rather than bulk text dumping.
 */
class ReadFileTool(private val fs: FileSystem) : Tool<ReadFileTool.Input, ReadFileTool.Output> {
    @Serializable
    data class Input(
        val path: String,
        val maxBytes: Long? = null,
    )

    @Serializable
    data class Output(
        val path: String,
        val bytes: Long,
        val content: String,
    )

    override val id: String = "read_file"
    override val helpText: String =
        "Read a UTF-8 text file from the user's filesystem. Absolute paths only. " +
            "Use for subtitle files (.srt/.vtt), prompt templates, edit scripts — " +
            "NOT for Project JSON or anything under ~/.talevia/. For binary assets use import_media."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "fs.read",
        patternFrom = { raw -> extractPathPattern(raw, field = "path") },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to the file to read.")
            }
            putJsonObject("maxBytes") {
                put("type", "integer")
                put("description", "Reject the read if file size exceeds this cap (default 10 MB).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val cap = input.maxBytes ?: FileSystem.DEFAULT_MAX_READ_BYTES
        val content = fs.readText(input.path, maxBytes = cap)
        val sizeBytes = content.encodeToByteArray().size.toLong()
        val outputForLlm = if (sizeBytes <= INLINE_THRESHOLD) {
            content
        } else {
            // Too big to inline; the agent should inspect `data.content` or re-read
            // with a smaller file. We still return the full content in the data
            // payload so UIs can display it.
            "read $sizeBytes bytes from ${input.path} (content too large to inline; see tool output data)"
        }
        return ToolResult(
            title = "read_file ${input.path}",
            outputForLlm = outputForLlm,
            data = Output(path = input.path, bytes = sizeBytes, content = content),
        )
    }

    private companion object {
        /** Inline content up to this size directly into outputForLlm. ~1/2 page of plain text. */
        const val INLINE_THRESHOLD: Long = 4_096
    }
}
