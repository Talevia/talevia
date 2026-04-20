package io.talevia.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.talevia.cli.bootstrap.SecretBootstrapResult
import io.talevia.cli.bootstrap.ensureProviderKey
import io.talevia.cli.repl.Repl
import io.talevia.cli.repl.buildInteractiveLineReader
import kotlinx.coroutines.runBlocking
import org.jline.terminal.TerminalBuilder
import kotlin.system.exitProcess

private class TaleviaCli : CliktCommand(name = "talevia") {
    private val resume by option(
        "--resume",
        help = "Resume the most recently updated session in the active project",
    ).flag()

    override fun run() {
        val code = runBlocking {
            val env = envWithDefaults()
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

fun main(args: Array<String>) = TaleviaCli().main(args)

/**
 * Mirror [io.talevia.desktop.desktopEnvWithDefaults] so the CLI picks up the
 * same `~/.talevia/talevia.db` + `~/.talevia/media` defaults when the user
 * hasn't set them explicitly. Load-bearing: without this the CLI falls back to
 * an in-memory DB and diverges from the desktop app's session history.
 */
private fun envWithDefaults(): Map<String, String> {
    val env = System.getenv().toMutableMap()
    val home = System.getProperty("user.home")
    val defaultRoot = java.io.File(home, ".talevia")
    if (env["TALEVIA_DB_PATH"].isNullOrBlank()) {
        env["TALEVIA_DB_PATH"] = java.io.File(defaultRoot, "talevia.db").absolutePath
    }
    if (env["TALEVIA_MEDIA_DIR"].isNullOrBlank()) {
        env["TALEVIA_MEDIA_DIR"] = java.io.File(defaultRoot, "media").absolutePath
    }
    return env
}
