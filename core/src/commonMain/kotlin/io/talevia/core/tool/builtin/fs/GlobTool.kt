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
 * Glob match over the user's external filesystem. Shares the `fs.list`
 * permission with [ListDirectoryTool] — reading directory structure is the
 * same disclosure either way; we reuse the permission so "Always allow
 * fs.list on /Users/xxx/Downloads" covers both list and glob under that dir.
 *
 * Glob syntax is the platform default (POSIX on JVM): `*` matches one segment,
 * double-star (two contiguous asterisks) spans directories, `?` matches a
 * single char. Pattern must be absolute so the walk root is unambiguous.
 */
class GlobTool(private val fs: FileSystem) : Tool<GlobTool.Input, GlobTool.Output> {
    @Serializable
    data class Input(
        val pattern: String,
        val maxMatches: Int? = null,
    )

    @Serializable
    data class Output(
        val pattern: String,
        val matches: List<String>,
        val truncated: Boolean,
    )

    override val id: String = "glob"
    override val helpText: String =
        "Find files matching a glob pattern. Absolute patterns only (e.g. /Users/xxx/Downloads/**.srt). " +
            "Prefer this over list_directory when discovering files by extension across subdirectories."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "fs.list",
        patternFrom = { raw -> extractPathPattern(raw, field = "pattern") },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "Absolute glob pattern (POSIX globbing; star, question-mark, [range], {alt}).")
            }
            putJsonObject("maxMatches") {
                put("type", "integer")
                put("description", "Cap on returned matches (default 1000).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("pattern"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val cap = input.maxMatches ?: FileSystem.DEFAULT_MAX_LIST_ENTRIES
        val res = fs.glob(input.pattern, maxMatches = cap)
        val suffix = if (res.truncated) " (truncated at $cap)" else ""
        return ToolResult(
            title = "glob ${input.pattern}",
            outputForLlm = buildString {
                append("${res.matches.size} matches for ${input.pattern}$suffix\n")
                res.matches.joinTo(this, separator = "\n") { "  $it" }
            },
            data = Output(pattern = input.pattern, matches = res.matches, truncated = res.truncated),
        )
    }
}
