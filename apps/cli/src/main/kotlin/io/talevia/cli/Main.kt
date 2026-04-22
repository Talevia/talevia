package io.talevia.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.talevia.cli.bootstrap.SecretBootstrapResult
import io.talevia.cli.bootstrap.ensureProviderKey
import io.talevia.cli.repl.Repl
import io.talevia.cli.repl.buildInteractiveLineReader
import io.talevia.core.logging.LogLevel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.jline.terminal.TerminalBuilder
import java.io.File
import kotlin.system.exitProcess

private class TaleviaCli : CliktCommand(name = "talevia") {
    private val resume by option(
        "--resume",
        help = "Resume the most recently updated session in the active project",
    ).flag()

    override fun run() {
        val code = runBlocking {
            val env = envWithDefaults()
            val home = System.getProperty("user.home")
            val logLevel = runCatching { LogLevel.valueOf(env["TALEVIA_CLI_LOG_LEVEL"]?.uppercase().orEmpty()) }
                .getOrElse { LogLevel.INFO }
            CliLoggers.install(File(home, ".talevia/cli.log"), logLevel)
            val terminal = TerminalBuilder.builder().system(true).build()
            val reader = buildInteractiveLineReader(terminal)

            when (ensureProviderKey(env, reader)) {
                SecretBootstrapResult.Ready -> Unit
                SecretBootstrapResult.Missing -> {
                    reader.printAbove(
                        "No LLM provider configured. Set ANTHROPIC_API_KEY / OPENAI_API_KEY / " +
                            "GEMINI_API_KEY in the environment or write it to " +
                            "~/.talevia/secrets.properties, then re-launch.",
                    )
                    return@runBlocking 2
                }
            }

            val container = CliContainer(env)
            try {
                Repl(container, terminal, reader, resume = resume).run()
            } finally {
                container.close()
            }
        }
        if (code != 0) exitProcess(code)
    }
}

/**
 * Top-level entry point.
 *
 * Before delegating to Clikt's option parsing we sniff [args] for the
 * file-bundle subcommand surface added in the file-bundle migration:
 *
 *  - `talevia open <path>`     — open the project bundle at `<path>`
 *  - `talevia new <path>`      — create a new bundle at `<path>` (optionally
 *                                with `--title "..."`)
 *  - `talevia <path>`          — open the bundle if it already contains
 *                                `talevia.json`, else create one
 *
 * Each form registers / loads the project via [io.talevia.core.domain.ProjectStore]
 * and then drops into the normal interactive REPL with the resolved project's
 * id surfaced. Anything else falls through to the existing Clikt-managed flow
 * (`talevia [--resume]`).
 *
 * TODO(file-bundle-migration): a separate agent will deliver an `OpenProjectTool`
 * that reuses this code from inside the agent loop. Until then we call the
 * store directly here.
 */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        val handled = handleProjectSubcommand(args)
        if (handled != null) {
            if (handled != 0) exitProcess(handled)
            return
        }
    }
    TaleviaCli().main(args)
}

/**
 * Process `open` / `new` / `<path>` subcommands. Returns the desired exit code
 * when the args matched, or null when the args should fall through to Clikt
 * (either no subcommand match, or the user passed only options like
 * `--resume` / `--help`).
 */
private fun handleProjectSubcommand(args: Array<String>): Int? {
    val first = args[0]
    return when {
        first == "open" -> {
            val path = args.getOrNull(1) ?: run {
                System.err.println("usage: talevia open <path>")
                return 2
            }
            runProjectFlow(path = path, mode = ProjectMode.Open, title = null, restArgs = args.drop(2))
        }

        first == "new" -> {
            val path = args.getOrNull(1) ?: run {
                System.err.println("usage: talevia new <path> [--title \"...\"]")
                return 2
            }
            val title = parseTitleOption(args.drop(2))
            runProjectFlow(path = path, mode = ProjectMode.New, title = title, restArgs = emptyList())
        }

        // Single positional path-like arg, no leading dash — treat as auto-detect.
        args.size == 1 && !first.startsWith("-") -> {
            val pathFile = File(first)
            val mode = if (File(pathFile, "talevia.json").exists()) ProjectMode.Open else ProjectMode.New
            runProjectFlow(path = first, mode = mode, title = null, restArgs = emptyList())
        }

        else -> null
    }
}

private enum class ProjectMode { Open, New }

private fun parseTitleOption(rest: List<String>): String? {
    val idx = rest.indexOfFirst { it == "--title" || it == "-t" }
    if (idx < 0) return null
    return rest.getOrNull(idx + 1)
}

/**
 * Boot the container, ensure the bundle exists / is open, and drop into the
 * REPL bound to that project. Mirrors [TaleviaCli.run] minus the resume
 * option (the user picked an explicit project; we don't auto-resume across
 * projects).
 */
private fun runProjectFlow(path: String, mode: ProjectMode, title: String?, restArgs: List<String>): Int = runBlocking {
    val env = envWithDefaults()
    val home = System.getProperty("user.home")
    val logLevel = runCatching { LogLevel.valueOf(env["TALEVIA_CLI_LOG_LEVEL"]?.uppercase().orEmpty()) }
        .getOrElse { LogLevel.INFO }
    CliLoggers.install(File(home, ".talevia/cli.log"), logLevel)
    val terminal = TerminalBuilder.builder().system(true).build()
    val reader = buildInteractiveLineReader(terminal)

    when (ensureProviderKey(env, reader)) {
        SecretBootstrapResult.Ready -> Unit
        SecretBootstrapResult.Missing -> {
            reader.printAbove(
                "No LLM provider configured. Set ANTHROPIC_API_KEY / OPENAI_API_KEY / " +
                    "GEMINI_API_KEY in the environment or write it to " +
                    "~/.talevia/secrets.properties, then re-launch.",
            )
            return@runBlocking 2
        }
    }

    val container = CliContainer(env)
    try {
        val absolute = File(path).absoluteFile.path.toPath()
        val resolved = when (mode) {
            ProjectMode.Open -> container.projects.openAt(absolute)
            ProjectMode.New -> container.projects.createAt(
                path = absolute,
                title = title ?: File(path).name.ifBlank { "Untitled" },
            )
        }
        reader.printAbove("Project ${resolved.id.value} ready at $absolute")
        // Honor a stray `--resume` token in restArgs (only meaningful for `open`).
        val resume = restArgs.contains("--resume")
        Repl(container, terminal, reader, resume = resume).run()
    } finally {
        container.close()
    }
    0
}

/**
 * Mirror [io.talevia.desktop.desktopEnvWithDefaults] so the CLI picks up the
 * same `~/.talevia/talevia.db` + `~/.talevia/projects` + `~/.talevia/recents.json`
 * defaults when the user hasn't set them explicitly. Load-bearing: without this
 * the CLI falls back to an in-memory DB and diverges from the desktop app's
 * session history. `TALEVIA_PROJECTS_HOME` and `TALEVIA_RECENTS_PATH` are
 * required by [CliContainer] (file-bundle ProjectStore); we default both to
 * `~/.talevia/...`.
 */
internal fun envWithDefaults(): Map<String, String> {
    val env = System.getenv().toMutableMap()
    val home = System.getProperty("user.home")
    val defaultRoot = java.io.File(home, ".talevia")
    if (env["TALEVIA_DB_PATH"].isNullOrBlank()) {
        env["TALEVIA_DB_PATH"] = java.io.File(defaultRoot, "talevia.db").absolutePath
    }
    if (env["TALEVIA_PROJECTS_HOME"].isNullOrBlank()) {
        env["TALEVIA_PROJECTS_HOME"] = java.io.File(defaultRoot, "projects").absolutePath
    }
    if (env["TALEVIA_RECENTS_PATH"].isNullOrBlank()) {
        env["TALEVIA_RECENTS_PATH"] = java.io.File(defaultRoot, "recents.json").absolutePath
    }
    return env
}
