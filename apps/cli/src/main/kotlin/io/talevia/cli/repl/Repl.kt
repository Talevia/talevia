package io.talevia.cli.repl

import io.talevia.cli.CliContainer
import io.talevia.cli.event.EventRouter
import io.talevia.cli.permission.StdinPermissionPrompt
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp
import sun.misc.Signal
import sun.misc.SignalHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
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
    private val terminal: Terminal,
    private val reader: LineReader,
    private val resume: Boolean,
) {
    private enum class Outcome { CONTINUE, EXIT }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(): Int = coroutineScope {
        val agent = container.newAgent()
            ?: error("SecretBootstrap reported ready but CliContainer has no default provider")
        val provider = container.providers.default!!
        println("talevia cli · db=${container.dbPath} · provider=${provider.id}")

        val projectId = bootstrapProject()
        var sessionId = bootstrapSession(projectId)
        var modelId = defaultModelFor(provider.id)
        println("project=${projectId.value.take(8)} · session=${sessionId.value.take(8)} · model=$modelId")
        println("type /help for commands, /exit to quit (Ctrl+D also works)")
        println()

        val renderer = Renderer(terminal)

        val routerScope = CoroutineScope(coroutineContext + SupervisorJob())
        val router = EventRouter(
            bus = container.bus,
            sessions = container.sessions,
            renderer = renderer,
            activeSessionId = { sessionId },
        )
        router.start(routerScope)
        val permissionPrompt = StdinPermissionPrompt(
            bus = container.bus,
            permissions = container.permissions,
            renderer = renderer,
            lineReader = reader,
            permissionRules = container.permissionRules,
            activeSessionId = { sessionId },
        )
        permissionPrompt.start(routerScope)

        // Two-stage Ctrl+C: during agent.run we cancel the current turn; otherwise
        // the handler falls through to `exitProcess(130)`. JLine only throws
        // UserInterruptException while blocked in readLine, so we also need a
        // POSIX signal handler for the "run in flight" window.
        val runActive = AtomicBoolean(false)
        val previousSigint: SignalHandler? = runCatching {
            Signal.handle(
                Signal("INT"),
                SignalHandler {
                    if (runActive.get()) {
                        routerScope.launch { agent.cancel(sessionId) }
                        System.err.println()
                        System.err.println("(cancelling — Ctrl+C again to force quit)")
                    } else {
                        System.err.println()
                        exitProcess(130)
                    }
                },
            )
        }.getOrNull()

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

                if (trimmed.startsWith("/")) {
                    val outcome = handleSlash(
                        raw = trimmed,
                        renderer = renderer,
                        projectId = projectId,
                        currentSession = sessionId,
                        onSwitchSession = { sessionId = it },
                        currentModel = modelId,
                        onSwitchModel = { modelId = it },
                    )
                    if (outcome == Outcome.EXIT) break
                    continue
                }

                // Run the turn on a child launch so the SIGINT handler can cancel
                // just the turn rather than the REPL scope itself.
                var turnError: Throwable? = null
                val turnJob = launch {
                    try {
                        val assistant = agent.run(
                            RunInput(
                                sessionId = sessionId,
                                text = trimmed,
                                model = ModelRef(provider.id, modelId),
                                permissionRules = container.permissionRules.toList(),
                            ),
                        )
                        // Fallback for providers that upsert the final Part.Text without firing deltas;
                        // Renderer.ensureAssistantText is idempotent, so this no-ops when streaming worked.
                        container.sessions.listParts(assistant.id)
                            .filterIsInstance<Part.Text>()
                            .forEach { renderer.ensureAssistantText(it.id, it.text) }
                    } catch (t: Throwable) {
                        turnError = t
                    }
                }
                runActive.set(true)
                try {
                    turnJob.join()
                } finally {
                    runActive.set(false)
                }
                turnError?.let { t ->
                    if (t is CancellationException) renderer.println("(cancelled)")
                    else renderer.error("agent failed: ${t.message ?: t::class.simpleName}")
                }
                renderer.endTurn()
            }
        } finally {
            previousSigint?.let { runCatching { Signal.handle(Signal("INT"), it) } }
            permissionPrompt.stop()
            router.stop()
            routerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
        0
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun handleSlash(
        raw: String,
        renderer: Renderer,
        projectId: ProjectId,
        currentSession: SessionId,
        onSwitchSession: (SessionId) -> Unit,
        currentModel: String,
        onSwitchModel: (String) -> Unit,
    ): Outcome {
        val body = raw.removePrefix("/")
        val parts = body.split(Regex("\\s+"), limit = 2)
        val name = parts[0].lowercase()
        val args = parts.getOrNull(1)?.trim().orEmpty()

        when (name) {
            "exit", "quit" -> return Outcome.EXIT
            "help" -> renderer.println(helpText())
            "clear" -> {
                terminal.puts(InfoCmp.Capability.clear_screen)
                terminal.flush()
            }
            "new" -> {
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
                onSwitchSession(fresh)
                renderer.println("✓ new session ${fresh.value.take(8)}")
            }
            "sessions" -> renderer.println(sessionsTable(projectId, currentSession))
            "resume" -> {
                if (args.isBlank()) {
                    renderer.println("usage: /resume <id-prefix>")
                } else {
                    val matches = container.sessions.listSessions(projectId)
                        .filter { !it.archived && it.id.value.startsWith(args) }
                    when (matches.size) {
                        0 -> renderer.println("no session id starts with '$args'")
                        1 -> {
                            onSwitchSession(matches.single().id)
                            renderer.println("✓ switched to ${matches.single().id.value.take(8)} · ${matches.single().title}")
                        }
                        else -> {
                            renderer.println("ambiguous: ${matches.size} sessions match '$args'")
                            matches.take(5).forEach { renderer.println("  · ${it.id.value.take(12)} · ${it.title}") }
                        }
                    }
                }
            }
            "model" -> {
                if (args.isBlank()) {
                    renderer.println("current: ${container.providers.default!!.id}/$currentModel")
                } else {
                    onSwitchModel(args)
                    renderer.println("✓ model=${container.providers.default!!.id}/$args (takes effect next turn)")
                }
            }
            "cost" -> renderer.println(costSummary(currentSession))
            else -> renderer.println("unknown command: /$name (try /help)")
        }
        return Outcome.CONTINUE
    }

    private fun helpText(): String = buildString {
        appendLine("slash commands:")
        val nameWidth = SLASH_COMMANDS.maxOf { (it.name + " " + it.argHint).trim().length }
        SLASH_COMMANDS.forEach { cmd ->
            val lhs = (cmd.name + " " + cmd.argHint).trim().padEnd(nameWidth)
            appendLine("  $lhs  ${cmd.help}")
        }
    }.trimEnd()

    private suspend fun sessionsTable(projectId: ProjectId, current: SessionId): String {
        val sessions = container.sessions.listSessions(projectId)
            .filter { !it.archived }
            .sortedByDescending { it.updatedAt }
        if (sessions.isEmpty()) return "no sessions in this project"
        return buildString {
            appendLine("sessions (most recent first):")
            sessions.forEach { s ->
                val marker = if (s.id == current) "*" else " "
                appendLine("  $marker ${s.id.value.take(12)}  ${s.updatedAt}  ${s.title}")
            }
        }.trimEnd()
    }

    private suspend fun costSummary(sessionId: SessionId): String {
        val assistants = container.sessions.listMessages(sessionId).filterIsInstance<Message.Assistant>()
        if (assistants.isEmpty()) return "no assistant messages yet in this session"
        var tokens = TokenUsage.ZERO
        var usd = 0.0
        assistants.forEach {
            tokens = TokenUsage(
                input = tokens.input + it.tokens.input,
                output = tokens.output + it.tokens.output,
                reasoning = tokens.reasoning + it.tokens.reasoning,
                cacheRead = tokens.cacheRead + it.tokens.cacheRead,
                cacheWrite = tokens.cacheWrite + it.tokens.cacheWrite,
            )
            usd += it.cost.usd
        }
        return "in=${tokens.input} · out=${tokens.output} · reasoning=${tokens.reasoning} · " +
            "cache r/w=${tokens.cacheRead}/${tokens.cacheWrite} · usd=${"%.5f".format(usd)}"
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
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}
