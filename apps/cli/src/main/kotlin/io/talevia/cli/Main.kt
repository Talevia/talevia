package io.talevia.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.talevia.cli.repl.Repl
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private class TaleviaCli : CliktCommand(name = "talevia") {
    private val resume by option(
        "--resume",
        help = "Resume the most recently updated session in the active project",
    ).flag()

    override fun run() {
        val code = runBlocking {
            val container = CliContainer(envWithDefaults())
            try {
                Repl(container, resume = resume).run()
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
