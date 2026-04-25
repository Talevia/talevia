package io.talevia.cli.repl

import io.talevia.cli.CliContainer
import io.talevia.cli.event.EventRouter
import io.talevia.cli.permission.StdinPermissionPrompt
import io.talevia.core.ProjectId
import io.talevia.core.agent.RunInput
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import sun.misc.Signal
import sun.misc.SignalHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Interactive REPL driver. Owns the [CliContainer], the active session,
 * the terminal, and the [EventRouter] that forwards bus events to the
 * [Renderer].
 *
 * The main loop is intentionally thin: read a line; if it starts with
 * `/`, hand off to [SlashCommandDispatcher]; otherwise call
 * [io.talevia.core.agent.Agent.run] and let [EventRouter] stream the
 * assistant response over the bus.
 *
 * Split out of the pre-2026-04-23 monolithic Repl per
 * `debt-split-cli-repl`. Repl keeps input-read + lifecycle (SIGINT,
 * EventRouter / PermissionPrompt start/stop, agent.run dispatch, turn
 * token summary); slash-command parsing + formatting lives in
 * [SlashCommandDispatcher].
 */
class Repl(
    private val container: CliContainer,
    private val terminal: Terminal,
    private val reader: LineReader,
    private val bootstrapMode: BootstrapMode = BootstrapMode.Auto,
    private val argSources: SlashArgSources = SlashArgSources(),
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(): Int = coroutineScope {
        val agent = container.newAgent()
            ?: error("SecretBootstrap reported ready but CliContainer has no default provider")
        val provider = container.providers.default!!

        // ANSI is fine in any sane TTY; disable when stdout is piped or when
        // the user opts out so downstream consumers (logs, scripts) get plain
        // text.
        val isTty = System.console() != null
        // Markdown repaint (cursor-up + clear-to-EOS, then re-render via
        // Mordant) is OFF by default: the cursor math is brittle when the
        // streamed reply contains wide (CJK) chars, wraps on a narrow
        // terminal, or runs after a permission prompt the Renderer didn't
        // observe. In those cases the clear erases more than it re-renders
        // and the user sees a blank where the reply should be. Opt in
        // with TALEVIA_CLI_MARKDOWN=on|1|true when you want bold/bullets
        // rendered instead of the raw streamed markdown.
        val mdEnv = System.getenv("TALEVIA_CLI_MARKDOWN").orEmpty().lowercase()
        val markdownEnabled = isTty && (mdEnv == "on" || mdEnv == "1" || mdEnv == "true")
        Styles.setEnabled(isTty)

        println("${Styles.banner("talevia cli")} ${Styles.meta("· db=${container.dbPath} · provider=${provider.id}")}")

        val projectId = bootstrapProject()
        val bootstrapResult = bootstrapSession(container.sessions, projectId, bootstrapMode)
        var sessionId = bootstrapResult.sessionId
        var modelId = defaultModelFor(provider.id)
        println(
            Styles.meta(
                "project=${projectId.value.take(8)} · session=${sessionId.value.take(8)} " +
                    "(${bootstrapResult.reason}) · model=$modelId",
            ),
        )
        println(Styles.meta("type /help for commands, /exit to quit (Ctrl+D also works)"))
        println()

        // Wire arg-completer producers now that projectId + sessionId are
        // settled. The reader was built before the container existed (it's
        // needed for the secret-bootstrap prompt), so the holder was empty
        // until this point. Both lambdas runBlocking on the local SQLite
        // store — completer thread blocks for sub-millisecond reads.
        argSources.sessionIds = {
            kotlinx.coroutines.runBlocking {
                container.sessions.listSessions(projectId).map { it.id.value }
            }
        }
        argSources.messageIds = {
            kotlinx.coroutines.runBlocking {
                container.sessions.listMessages(sessionId).map { it.id.value }
            }
        }

        // Hydrate the in-memory permission-history aggregator from
        // SQLite for the active session — so cross-restart "user
        // already rejected this" memory is visible to the agent on its
        // very first read. No-op when no decisions persisted yet.
        container.permissionHistory.hydrateFromStore(sessionId)

        val renderer = Renderer(
            terminal,
            markdownEnabled = markdownEnabled,
            // In-place rewrites (tool ⟳→✓, same-jobId progress bar) only need
            // ANSI cursor control. Tie them to TTY so they kick in even when
            // the richer Mordant markdown repaint is opted-out of.
            ansiEnabled = isTty,
        )

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
            persistence = container.permissionRulesPersistence,
        )
        permissionPrompt.start(routerScope)

        val dispatcher = SlashCommandDispatcher(container, terminal, renderer)

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
                        System.err.println(Styles.meta("(cancelling — Ctrl+C again to force quit)"))
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
                    reader.readLine(Styles.prompt("> "))
                } catch (_: UserInterruptException) {
                    renderer.println(Styles.meta("(Ctrl+D or /exit to quit)"))
                    continue
                } catch (_: EndOfFileException) {
                    break
                } ?: break

                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("/")) {
                    val outcome = dispatcher.handle(
                        raw = trimmed,
                        projectId = projectId,
                        currentSession = sessionId,
                        onSwitchSession = { sessionId = it },
                        currentModel = modelId,
                        onSwitchModel = { modelId = it },
                    )
                    if (outcome == SlashCommandDispatcher.Outcome.EXIT) break
                    continue
                }

                // Run the turn on a child launch so the SIGINT handler can cancel
                // just the turn rather than the REPL scope itself.
                var turnError: Throwable? = null
                var turnAssistant: Message.Assistant? = null
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
                        turnAssistant = assistant
                        // Fallback for providers that upsert the final Part.Text without firing deltas;
                        // finalizeAssistantText is idempotent and also the path that triggers the
                        // markdown repaint when streaming did fire — so it's safe either way.
                        container.sessions.listParts(assistant.id)
                            .filterIsInstance<Part.Text>()
                            .forEach { renderer.finalizeAssistantText(it.id, it.text) }
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
                    if (t is CancellationException) renderer.println(Styles.meta("(cancelled)"))
                    else renderer.error("agent failed: ${t.message ?: t::class.simpleName}")
                }
                turnAssistant?.let { a ->
                    if (a.finish == FinishReason.ERROR) {
                        renderer.error("agent failed: ${a.error ?: "unknown error"}")
                    }
                    renderer.println(Styles.meta(turnTokenSummary(a.tokens)))
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

    /**
     * One-line per-turn token / cache summary printed after the
     * assistant finishes. `input` is always the total input (subsumes
     * cacheRead / cacheWrite on every provider since the unified
     * normalisation), so `cacheRead / input` is the real cache hit
     * rate regardless of backend.
     */
    private fun turnTokenSummary(t: TokenUsage): String {
        val hitPct = if (t.input > 0) (t.cacheRead.toDouble() / t.input.toDouble()) * 100.0 else 0.0
        val base = "· tokens in=${t.input} out=${t.output}"
        val reasoning = if (t.reasoning > 0) " reasoning=${t.reasoning}" else ""
        val cache = when {
            t.cacheRead == 0L && t.cacheWrite == 0L -> ""
            t.cacheWrite > 0L -> " · cache ${"%.1f".format(hitPct)}% (read=${t.cacheRead} write=${t.cacheWrite})"
            else -> " · cache ${"%.1f".format(hitPct)}% (read=${t.cacheRead})"
        }
        return base + reasoning + cache
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
}

internal fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-5.4-mini"
    else -> "default"
}
