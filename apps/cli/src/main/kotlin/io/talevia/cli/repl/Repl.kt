package io.talevia.cli.repl

import io.talevia.cli.CliContainer
import io.talevia.cli.event.EventRouter
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Interactive REPL driver. Owns the [CliContainer], the active session, the
 * terminal, and the [EventRouter] that forwards bus events to the [Renderer].
 *
 * The main loop is intentionally thin: read a line, dispatch slash commands,
 * otherwise call [io.talevia.core.agent.Agent.run] and let [EventRouter] stream
 * the assistant response over the bus.
 */
class Repl(
    private val container: CliContainer,
    private val resume: Boolean,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(): Int = coroutineScope {
        val agent = container.newAgent()
        if (agent == null) {
            System.err.println(
                "No LLM provider configured. Set ANTHROPIC_API_KEY / OPENAI_API_KEY / GEMINI_API_KEY " +
                    "in the environment or write it to ~/.talevia/secrets.properties, then re-launch.",
            )
            return@coroutineScope 2
        }
        val provider = container.providers.default!!
        println("talevia cli · db=${container.dbPath} · provider=${provider.id}")

        val projectId = bootstrapProject()
        var sessionId = bootstrapSession(projectId)
        println("project=${projectId.value.take(8)} · session=${sessionId.value.take(8)}")
        println("type /exit to quit (Ctrl+D also works)")
        println()

        val terminal = TerminalBuilder.builder().system(true).build()
        val reader = LineReaderBuilder.builder().terminal(terminal).build()
        val renderer = Renderer(terminal)

        val routerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val router = EventRouter(container.bus, renderer, activeSessionId = { sessionId })
        router.start(routerScope)

        try {
            while (true) {
                val line = try {
                    reader.readLine("> ")
                } catch (_: UserInterruptException) {
                    renderer.println("(Ctrl+D or /exit to quit)")
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
                    // Fallback for providers that upsert the final Part.Text without firing deltas;
                    // Renderer.ensureAssistantText is idempotent, so this no-ops when streaming worked.
                    container.sessions.listParts(assistant.id)
                        .filterIsInstance<Part.Text>()
                        .forEach { renderer.ensureAssistantText(it.id, it.text) }
                }.onFailure { t ->
                    renderer.error("agent failed: ${t.message ?: t::class.simpleName}")
                }
                renderer.endTurn()
            }
        } finally {
            router.stop()
            routerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        0
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun bootstrapProject(): ProjectId {
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
    private suspend fun bootstrapSession(projectId: ProjectId): SessionId {
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
}

internal fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-4o"
    else -> "default"
}
