package io.talevia.core.tool.builtin.shell

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.ProcessRunner
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Run a shell command via [ProcessRunner]. The agent's escape hatch for the
 * long tail of "I need to call `git` / `ffprobe` / `ls -la` right now" without
 * us having to mint a dedicated tool for each. Mirrors Claude Code's Bash and
 * OpenCode's `tool/bash.ts`.
 *
 * Permission is `bash.exec` with the **first command token** as the pattern
 * (so `git status`, `git diff`, `git log` all bucket under `git`). This makes
 * "Always allow bash `git`" a useful rule — without it, the prompt pattern
 * would be the full command string, which never repeats exactly.
 *
 * Non-zero exit is returned as data, not thrown — the agent should be able
 * to read stderr and adjust. We only throw on impossible-to-start errors
 * (shell missing, invalid working directory).
 */
class BashTool(private val runner: ProcessRunner) : Tool<BashTool.Input, BashTool.Output> {
    @Serializable
    data class Input(
        val command: String,
        val workingDir: String? = null,
        val timeoutMillis: Long? = null,
    )

    @Serializable
    data class Output(
        val command: String,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        val durationMillis: Long,
    )

    override val id: String = "bash"
    override val helpText: String =
        "Run a shell command via `sh -c`. Returns stdout, stderr, exit code. ASK permission " +
            "keyed on the first command token (so `git status` / `git diff` share one rule). " +
            "Use for ad-hoc tool calls (git / ls / ffprobe). NOT for long-running processes, " +
            "interactive commands, or anything covered by a dedicated tool (read_file / write_file / " +
            "edit_file / glob / grep / import_media / export)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec(
        permission = "bash.exec",
        patternFrom = { raw -> extractCommandPattern(raw) },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "Shell command interpreted by `sh -c`. Pipes / redirects / quoting all work.")
            }
            putJsonObject("workingDir") {
                put("type", "string")
                put("description", "Absolute directory to run in. Optional; defaults to the process CWD.")
            }
            putJsonObject("timeoutMillis") {
                put("type", "integer")
                put(
                    "description",
                    "Per-command timeout in milliseconds. Default 30000, hard max " +
                        "${ProcessRunner.HARD_MAX_TIMEOUT_MILLIS}. On timeout the process is killed " +
                        "and `timedOut=true` is returned.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("command"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.command.isNotBlank()) { "command must not be blank" }
        val timeout = input.timeoutMillis ?: ProcessRunner.DEFAULT_TIMEOUT_MILLIS
        val result = runner.run(
            command = input.command,
            workingDir = input.workingDir,
            timeoutMillis = timeout,
        )
        val llm = buildString {
            append("$ ").append(input.command).append('\n')
            append("exit=").append(result.exitCode)
            if (result.timedOut) append(" (timed out after ${timeout}ms)")
            if (result.truncated) append(" (output truncated)")
            append('\n')
            if (result.stdout.isNotEmpty()) {
                append("--- stdout ---\n")
                append(result.stdout)
                if (!result.stdout.endsWith('\n')) append('\n')
            }
            if (result.stderr.isNotEmpty()) {
                append("--- stderr ---\n")
                append(result.stderr)
                if (!result.stderr.endsWith('\n')) append('\n')
            }
        }
        return ToolResult(
            title = "bash ${input.command.take(60)}${if (input.command.length > 60) "…" else ""}",
            outputForLlm = llm.trimEnd(),
            data = Output(
                command = input.command,
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = result.timedOut,
                truncated = result.truncated,
                durationMillis = result.durationMillis,
            ),
        )
    }

    internal companion object {
        /**
         * Extract a stable pattern for permission rules: the first token of
         * the command, stripped of quoting. `git commit -m "foo"` → `git`;
         * `./gradlew test` → `./gradlew`; `ls -la | wc` → `ls`. Fallback to
         * `"*"` on any parse failure so the dispatcher stays safe.
         */
        internal fun extractCommandPattern(
            inputJson: String,
            json: Json = JsonConfig.default,
        ): String = runCatching {
            val raw = json.parseToJsonElement(inputJson).jsonObject["command"]?.jsonPrimitive?.content
                ?: return@runCatching null
            firstToken(raw)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "*"

        private fun firstToken(command: String): String {
            val trimmed = command.trimStart()
            if (trimmed.isEmpty()) return ""
            val end = trimmed.indexOfFirst { it.isWhitespace() || it in ";|&><" }
            return if (end < 0) trimmed else trimmed.substring(0, end)
        }
    }
}
