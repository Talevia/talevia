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
 * Regex search across UTF-8 text files under an absolute path. Complements
 * [GlobTool] (find files by name) and [ReadFileTool] (read one file) — the
 * missing verb when the agent needs to locate content by pattern (e.g. "which
 * subtitle file mentions 'opening ceremony'?", "find the TODO we left in the
 * edit script"). Binary files, files over the size cap, and files that fail
 * UTF-8 decode are silently skipped rather than erroring the whole search.
 *
 * Permission reuses `fs.read` — grep discloses the same class of information
 * (file contents) as read_file, just via a server-side filter. Pattern is the
 * search root, so an "Always allow" decision naturally scopes to that path.
 * OpenCode's `tool/grep.ts` uses ripgrep; we stay on the JDK regex engine so
 * no external binary dependency leaks into the Core.
 */
class GrepTool(private val fs: FileSystem) : Tool<GrepTool.Input, GrepTool.Output> {
    @Serializable
    data class Input(
        val path: String,
        val pattern: String,
        val caseInsensitive: Boolean = false,
        val include: String? = null,
        val maxMatches: Int? = null,
    )

    @Serializable
    data class Output(
        val path: String,
        val pattern: String,
        val matches: List<Match>,
        val filesScanned: Int,
        val truncated: Boolean,
    )

    @Serializable
    data class Match(
        val path: String,
        val line: Int,
        val content: String,
    )

    override val id: String = "grep"
    override val helpText: String =
        "Regex search file contents under an absolute path (file or directory, recursive). " +
            "`pattern` is a regex (Kotlin/Java flavour). Optional `include` is a glob on the " +
            "file's absolute path (e.g. `**.kt`) to scope to a subset. `caseInsensitive=true` " +
            "flags the regex case-insensitive. Binary / non-UTF-8 / oversized files are " +
            "silently skipped. Prefer this over reading every file individually when looking " +
            "for specific content. Returns up to 200 matching lines by default."
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
                put("description", "Absolute path (directory walked recursively, or a single regular file).")
            }
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "Regex applied per line (Kotlin/Java flavour, e.g. `TODO.*\\bMei\\b`).")
            }
            putJsonObject("caseInsensitive") {
                put("type", "boolean")
                put("description", "Match regardless of case. Default false.")
            }
            putJsonObject("include") {
                put("type", "string")
                put("description", "Optional glob filter on the file's absolute path (e.g. `**.srt`).")
            }
            putJsonObject("maxMatches") {
                put("type", "integer")
                put("description", "Cap on returned matches (default 200).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("pattern"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val cap = input.maxMatches ?: FileSystem.DEFAULT_MAX_GREP_MATCHES
        val res = fs.grep(
            path = input.path,
            pattern = input.pattern,
            caseInsensitive = input.caseInsensitive,
            include = input.include,
            maxMatches = cap,
        )
        val suffix = if (res.truncated) " (truncated at $cap)" else ""
        val llmOutput = buildString {
            append("${res.matches.size} match")
            if (res.matches.size != 1) append("es")
            append(" across ")
            append(res.filesScanned)
            append(" file")
            if (res.filesScanned != 1) append("s")
            append(suffix)
            if (res.matches.isNotEmpty()) append('\n')
            res.matches.joinTo(this, separator = "\n") { "${it.path}:${it.line}: ${it.content}" }
        }
        return ToolResult(
            title = "grep \"${input.pattern}\" ${input.path}",
            outputForLlm = llmOutput,
            data = Output(
                path = input.path,
                pattern = input.pattern,
                matches = res.matches.map { Match(it.path, it.line, it.content) },
                filesScanned = res.filesScanned,
                truncated = res.truncated,
            ),
        )
    }
}
