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
 * Non-recursive directory listing. Guarded by `fs.list` (default ASK) with the
 * listed `path` as the pattern. For recursive discovery (find every .srt under
 * ~/Downloads) use [GlobTool] — it's what the LLM should reach for instead of
 * walking manually with list.
 */
class ListDirectoryTool(private val fs: FileSystem) :
    Tool<ListDirectoryTool.Input, ListDirectoryTool.Output> {
    @Serializable
    data class Input(
        val path: String,
        val maxEntries: Int? = null,
    )

    @Serializable
    data class EntryOut(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedEpochMs: Long,
    )

    @Serializable
    data class Output(
        val path: String,
        val entries: List<EntryOut>,
        val truncated: Boolean,
    )

    override val id: String = "list_directory"
    override val helpText: String =
        "List entries in a directory (non-recursive). Use for orienting before read_file / " +
            "glob. Absolute paths only."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "fs.list",
        patternFrom = { raw -> extractPathPattern(raw, field = "path") },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path of the directory to list.")
            }
            putJsonObject("maxEntries") {
                put("type", "integer")
                put("description", "Cap on returned entries (default 1000).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val cap = input.maxEntries ?: FileSystem.DEFAULT_MAX_LIST_ENTRIES
        val res = fs.list(input.path, maxEntries = cap)
        val entries = res.entries.map {
            EntryOut(
                name = it.name,
                isDirectory = it.isDirectory,
                sizeBytes = it.sizeBytes,
                modifiedEpochMs = it.modifiedEpochMs,
            )
        }
        val suffix = if (res.truncated) " (truncated at $cap)" else ""
        return ToolResult(
            title = "list_directory ${input.path}",
            outputForLlm = buildString {
                append("listed ${entries.size} entries in ${input.path}$suffix\n")
                entries.joinTo(this, separator = "\n") { e ->
                    val kind = if (e.isDirectory) "d" else "-"
                    "  $kind ${e.name}" + if (!e.isDirectory) " (${e.sizeBytes}B)" else ""
                }
            },
            data = Output(path = input.path, entries = entries, truncated = res.truncated),
        )
    }
}
