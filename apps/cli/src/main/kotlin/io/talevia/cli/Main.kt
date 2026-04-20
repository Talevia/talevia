package io.talevia.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import kotlin.system.exitProcess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private class TaleviaCli : CliktCommand(name = "talevia") {
    private val resume by option("--resume", help = "Resume the most recently updated session in the active project").flag()

    override fun run() {
        runBlocking {
            repl(resume = resume)
        }
    }
}

fun main(args: Array<String>) = TaleviaCli().main(args)

/**
 * Minimal non-streaming REPL. Reads a line, hands it to [io.talevia.core.agent.Agent.run],
 * and prints the assistant's final text parts. Streaming, tool visualisation, permission
 * prompts, and slash commands land in follow-up steps — see plan file.
 */
private suspend fun repl(resume: Boolean) {
    val container = CliContainer(envWithDefaults())
    try {
        val agent = container.newAgent()
        if (agent == null) {
            System.err.println(
                "No LLM provider configured. Set ANTHROPIC_API_KEY or OPENAI_API_KEY in the " +
                    "environment or write it to ~/.talevia/secrets.properties, then re-launch.",
            )
            exitProcess(2)
        }
        val provider = container.providers.default!!
        println("talevia cli · db=${container.dbPath} · provider=${provider.id}")

        val projectId = bootstrapProject(container)
        val sessionId = bootstrapSession(container, projectId, resume = resume)
        println("project=${projectId.value.take(8)} · session=${sessionId.value.take(8)}")
        println("type /exit to quit (Ctrl+D also works)")
        println()

        val terminal = TerminalBuilder.builder().system(true).build()
        val reader = LineReaderBuilder.builder().terminal(terminal).build()

        while (true) {
            val line = try {
                reader.readLine("> ")
            } catch (_: UserInterruptException) {
                // Ctrl+C on an empty prompt. Agent cancellation lives in a follow-up step;
                // for now we just bounce the user back to the prompt.
                println("(Ctrl+D or /exit to quit)")
                continue
            } catch (_: EndOfFileException) {
                break
            } ?: break

            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "/exit" || trimmed == "/quit") break

            runCatching {
                val assistant = agent.run(
                    RunInput(
                        sessionId = sessionId,
                        text = trimmed,
                        model = ModelRef(provider.id, defaultModelFor(provider.id)),
                        permissionRules = container.permissionRules.toList(),
                    ),
                )
                val text = container.sessions.listParts(assistant.id)
                    .filterIsInstance<Part.Text>()
                    .joinToString("") { it.text }
                println(if (text.isNotBlank()) text else "(no text response)")
                println()
            }.onFailure { t ->
                System.err.println("agent failed: ${t.message ?: t::class.simpleName}")
            }
        }
    } finally {
        container.close()
    }
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun bootstrapProject(container: CliContainer): ProjectId {
    val latest = container.projects.listSummaries().maxByOrNull { it.updatedAtEpochMs }
    if (latest != null) return ProjectId(latest.id)

    val fresh = ProjectId(Uuid.random().toString())
    container.projects.upsert(
        "Untitled",
        Project(id = fresh, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
    )
    return fresh
}

@OptIn(ExperimentalUuidApi::class)
private suspend fun bootstrapSession(
    container: CliContainer,
    projectId: ProjectId,
    resume: Boolean,
): SessionId {
    if (resume) {
        val existing = container.sessions.listSessions(projectId)
            .filter { !it.archived }
            .maxByOrNull { it.updatedAt }
        if (existing != null) return existing.id
    }
    val fresh = SessionId(Uuid.random().toString())
    val now = Clock.System.now()
    container.sessions.createSession(
        Session(
            id = fresh,
            projectId = projectId,
            title = "Chat",
            createdAt = now,
            updatedAt = now,
        ),
    )
    return fresh
}

private fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-4o"
    else -> "default"
}

/**
 * Mirror [desktopEnvWithDefaults] in desktop/Main.kt so the CLI picks up the same
 * `~/.talevia/talevia.db` + `~/.talevia/media` defaults when the user hasn't set
 * them explicitly. Load-bearing: without this the CLI falls back to an in-memory
 * DB and diverges from the desktop app's session history.
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
