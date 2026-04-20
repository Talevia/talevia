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
 * Find-and-replace on a single UTF-8 text file. Much cheaper than
 * [WriteFileTool] when the change is local: the model sends the matching
 * snippet and the replacement instead of re-emitting the entire file. Mirrors
 * Claude Code's Edit and OpenCode's `tool/edit.ts`.
 *
 * Uniqueness is enforced by default — `oldString` must appear exactly once in
 * the file so the edit is unambiguous. For deliberate bulk replacement the
 * caller flips `replaceAll=true`. Either mode fails loudly if the string
 * appears zero times; the agent gets a clear error rather than a silent no-op.
 *
 * Permission reuses `fs.write`. Same pattern field as `write_file`, so an
 * "Always allow fs.write on ~/Documents" decision covers both tools.
 */
class EditTool(private val fs: FileSystem) : Tool<EditTool.Input, EditTool.Output> {
    @Serializable
    data class Input(
        val path: String,
        val oldString: String,
        val newString: String,
        val replaceAll: Boolean = false,
    )

    @Serializable
    data class Output(
        val path: String,
        val replacements: Int,
        val bytesWritten: Long,
    )

    override val id: String = "edit_file"
    override val helpText: String =
        "Find-and-replace inside a UTF-8 text file. `oldString` must be literally present in " +
            "the file and unique (unless `replaceAll=true`). Fails if it doesn't match or matches " +
            "more than once with replaceAll=false. Use over `write_file` when the change is local — " +
            "sends the diff, not the whole file. Not for Project JSON / ~/.talevia/ / media catalog."
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
                put("description", "Absolute path of the file to edit (must exist).")
            }
            putJsonObject("oldString") {
                put("type", "string")
                put("description", "Literal substring to find. Must be present, and unique unless replaceAll=true.")
            }
            putJsonObject("newString") {
                put("type", "string")
                put("description", "Replacement. May be empty to delete the match.")
            }
            putJsonObject("replaceAll") {
                put("type", "boolean")
                put(
                    "description",
                    "If true, replace every occurrence; otherwise oldString must be unique. Default false.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("oldString"), JsonPrimitive("newString"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.oldString.isNotEmpty()) {
            "oldString must not be empty — use write_file to create / overwrite a file"
        }
        require(input.oldString != input.newString) {
            "oldString and newString are identical — no edit would occur"
        }
        val content = fs.readText(input.path)
        val occurrences = countOccurrences(content, input.oldString)
        when {
            occurrences == 0 ->
                throw IllegalArgumentException(
                    "oldString not found in ${input.path}. " +
                        "Read the file first to confirm the exact substring to match.",
                )
            occurrences > 1 && !input.replaceAll ->
                throw IllegalArgumentException(
                    "oldString matches $occurrences times in ${input.path}. " +
                        "Pass replaceAll=true to replace all, or widen oldString to uniquely identify the site.",
                )
        }
        val updated = if (input.replaceAll) {
            content.replace(input.oldString, input.newString)
        } else {
            val idx = content.indexOf(input.oldString)
            content.substring(0, idx) + input.newString + content.substring(idx + input.oldString.length)
        }
        val replacements = if (input.replaceAll) occurrences else 1
        val bytes = fs.writeText(input.path, updated)
        return ToolResult(
            title = "edit_file ${input.path}",
            outputForLlm = "replaced $replacements occurrence${if (replacements != 1) "s" else ""} in ${input.path}",
            data = Output(path = input.path, replacements = replacements, bytesWritten = bytes),
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + needle.length
        }
        return count
    }
}
