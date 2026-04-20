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
import io.talevia.core.session.SessionRevert
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.currentTodos
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

        // ANSI is fine in any sane TTY; disable when stdout is piped or when
        // the user opts out so downstream consumers (logs, scripts) get plain
        // text. The same flag also gates Phase-2 markdown repaints.
        val isTty = System.console() != null
        val mdEnv = System.getenv("TALEVIA_CLI_MARKDOWN").orEmpty().lowercase()
        val markdownEnabled = isTty && mdEnv != "off" && mdEnv != "0" && mdEnv != "false"
        Styles.setEnabled(isTty)

        println("${Styles.banner("talevia cli")} ${Styles.meta("· db=${container.dbPath} · provider=${provider.id}")}")

        val projectId = bootstrapProject()
        var sessionId = bootstrapSession(projectId)
        var modelId = defaultModelFor(provider.id)
        println(
            Styles.meta(
                "project=${projectId.value.take(8)} · session=${sessionId.value.take(8)} · model=$modelId",
            ),
        )
        println(Styles.meta("type /help for commands, /exit to quit (Ctrl+D also works)"))
        println()

        val renderer = Renderer(terminal, markdownEnabled = markdownEnabled)

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
                turnAssistant?.let { renderer.println(Styles.meta(turnTokenSummary(it.tokens))) }
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
                renderer.println("${Styles.ok("✓")} new session ${Styles.accent(fresh.value.take(8))}")
            }
            "sessions" -> renderer.println(sessionsTable(projectId, currentSession))
            "resume" -> {
                if (args.isBlank()) {
                    renderer.println(Styles.meta("usage: /resume <id-prefix>"))
                } else {
                    val matches = container.sessions.listSessions(projectId)
                        .filter { !it.archived && it.id.value.startsWith(args) }
                    when (matches.size) {
                        0 -> renderer.println(Styles.meta("no session id starts with '$args'"))
                        1 -> {
                            onSwitchSession(matches.single().id)
                            renderer.println(
                                "${Styles.ok("✓")} switched to ${Styles.accent(matches.single().id.value.take(8))} ${Styles.meta("· ${matches.single().title}")}",
                            )
                        }
                        else -> {
                            renderer.println(Styles.meta("ambiguous: ${matches.size} sessions match '$args'"))
                            matches.take(5).forEach {
                                renderer.println(Styles.meta("  · ${it.id.value.take(12)} · ${it.title}"))
                            }
                        }
                    }
                }
            }
            "model" -> {
                if (args.isBlank()) {
                    renderer.println("${Styles.meta("current:")} ${container.providers.default!!.id}/$currentModel")
                } else {
                    onSwitchModel(args)
                    renderer.println(
                        "${Styles.ok("✓")} model=${container.providers.default!!.id}/$args ${Styles.meta("(takes effect next turn)")}",
                    )
                }
            }
            "cost" -> renderer.println(costSummary(currentSession))
            "todos" -> renderer.println(todosSummary(currentSession))
            "status" -> renderer.println(statusLine(projectId, currentSession, currentModel))
            "history" -> renderer.println(historyTable(currentSession))
            "revert" -> renderer.println(handleRevert(currentSession, projectId, args))
            "fork" -> {
                val result = handleFork(currentSession, args)
                renderer.println(result.message)
                result.newSessionId?.let { onSwitchSession(it) }
            }
            else -> {
                val suggestion = suggestSlash("/$name")
                val hint = suggestion?.let { " · did you mean ${Styles.accent(it.name)}?" } ?: ""
                renderer.println(Styles.meta("unknown command: /$name (try /help)$hint"))
            }
        }
        return Outcome.CONTINUE
    }

    private data class ForkOutcome(val message: String, val newSessionId: SessionId?)

    /**
     * `/fork` with no arg duplicates the current session whole. With a prefix,
     * truncates the branch at that anchor — everything after it in the parent
     * stays in the parent, so the branched session is a clean continuation
     * point. Switches the REPL into the new branch on success (like `/resume`)
     * because the natural UX after typing `/fork` is "now keep editing in the
     * branch I just made".
     */
    private suspend fun handleFork(sessionId: SessionId, args: String): ForkOutcome {
        val anchor = if (args.isBlank()) {
            null
        } else {
            val matches = container.sessions.listMessages(sessionId)
                .filter { it.id.value.startsWith(args) }
            when (matches.size) {
                0 -> return ForkOutcome(
                    Styles.meta("no message id starts with '$args' (see /history)"),
                    newSessionId = null,
                )
                1 -> matches.single().id
                else -> {
                    val lines = buildString {
                        appendLine(Styles.meta("ambiguous: ${matches.size} messages match '$args'"))
                        matches.take(5).forEach {
                            appendLine(Styles.meta("  · ${it.id.value.take(16)}"))
                        }
                    }.trimEnd()
                    return ForkOutcome(lines, newSessionId = null)
                }
            }
        }
        val newId = container.sessions.fork(sessionId, newTitle = null, anchorMessageId = anchor)
        val detail = if (anchor == null) "full history" else "up to ${anchor.value.take(12)}"
        return ForkOutcome(
            "${Styles.ok("✓")} forked → ${Styles.accent(newId.value.take(12))} " +
                Styles.meta("· $detail · switched to new branch"),
            newSessionId = newId,
        )
    }

    /**
     * List recent turns as candidate anchors for `/revert`. We show every
     * message (user + assistant) because the simplest mental model is
     * "pick the line you want to keep, everything after it is dropped".
     * Message ids are surfaced as a 12-char prefix — same affordance as
     * `/resume` — enough for uniqueness in any realistic session length.
     */
    private suspend fun historyTable(sessionId: SessionId): String {
        val messages = container.sessions.listMessages(sessionId)
        if (messages.isEmpty()) return Styles.meta("no messages in this session yet")
        val partsBy = container.sessions.listSessionParts(sessionId, includeCompacted = true)
            .groupBy { it.messageId }
        return buildString {
            appendLine(Styles.meta("turns (oldest first) — /revert <idPrefix> keeps up to and including that line:"))
            messages.forEach { m ->
                val role = when (m) {
                    is Message.User -> "user"
                    is Message.Assistant -> "asst"
                }
                val id = Styles.accent(m.id.value.take(12))
                val parts = partsBy[m.id].orEmpty()
                val preview = parts.asSequence()
                    .filterIsInstance<Part.Text>()
                    .firstOrNull()?.text?.lineSequence()?.firstOrNull()
                    ?.take(60)
                    ?: parts.asSequence().filterIsInstance<Part.Tool>().firstOrNull()
                        ?.let { "(tool: ${it.toolId})" }
                    ?: "(no text)"
                appendLine("  $id  ${Styles.meta(role)}  $preview")
            }
        }.trimEnd()
    }

    /**
     * `/revert <prefix>` drives [SessionRevert]. Only 1 match allowed — picking
     * the wrong anchor silently would blow away the wrong N turns. An empty
     * prefix prints usage rather than reverting to the most recent turn
     * (which would be a no-op but also looks like a footgun).
     */
    private suspend fun handleRevert(
        sessionId: SessionId,
        projectId: ProjectId,
        args: String,
    ): String {
        if (args.isBlank()) return Styles.meta("usage: /revert <messageId-prefix> — see /history for ids")
        val messages = container.sessions.listMessages(sessionId)
        val matches = messages.filter { it.id.value.startsWith(args) }
        return when (matches.size) {
            0 -> Styles.meta("no message id starts with '$args' (see /history)")
            1 -> {
                val anchor = matches.single()
                val service = SessionRevert(
                    sessions = container.sessions,
                    projects = container.projects,
                    bus = container.bus,
                )
                val result = service.revertToMessage(sessionId, anchor.id, projectId)
                val timelineTail = if (result.appliedSnapshotPartId != null) {
                    " · timeline rolled back to ${result.restoredClipCount} clip(s) / " +
                        "${result.restoredTrackCount} track(s)"
                } else {
                    " · no prior timeline snapshot (timeline untouched)"
                }
                "${Styles.ok("✓")} reverted to ${Styles.accent(anchor.id.value.take(12))}" +
                    Styles.meta(" · dropped ${result.deletedMessages} message(s)$timelineTail")
            }
            else -> buildString {
                appendLine(Styles.meta("ambiguous: ${matches.size} messages match '$args'"))
                matches.take(5).forEach {
                    appendLine(Styles.meta("  · ${it.id.value.take(16)}"))
                }
            }.trimEnd()
        }
    }

    private fun helpText(): String = buildString {
        appendLine(Styles.meta("slash commands — tab-complete after '/' · also accepts a unique prefix:"))
        val nameWidth = SLASH_COMMANDS.maxOf { (it.name + " " + it.argHint).trim().length }
        SlashCategory.entries.forEach { cat ->
            val group = SLASH_COMMANDS.filter { it.category == cat }
            if (group.isEmpty()) return@forEach
            appendLine()
            appendLine("  ${Styles.accent(cat.title)}")
            group.forEach { cmd ->
                val lhs = (cmd.name + " " + cmd.argHint).trim().padEnd(nameWidth)
                appendLine("    ${Styles.toolId(lhs)}  ${Styles.meta(cmd.help)}")
            }
        }
    }.trimEnd()

    /**
     * Single-line `/status` summary. Answers "which session am I in, which
     * model is queued for the next turn, how much have we spent so far"
     * without flipping through `/sessions`, `/model`, and `/cost`.
     */
    private suspend fun statusLine(
        projectId: ProjectId,
        sessionId: SessionId,
        modelId: String,
    ): String {
        val session = container.sessions.getSession(sessionId)
        val title = session?.title ?: "(unknown)"
        val assistants = container.sessions.listMessages(sessionId).filterIsInstance<Message.Assistant>()
        val turns = assistants.size
        val tokens = assistants.fold(TokenUsage.ZERO) { acc, m ->
            TokenUsage(
                input = acc.input + m.tokens.input,
                output = acc.output + m.tokens.output,
                reasoning = acc.reasoning + m.tokens.reasoning,
                cacheRead = acc.cacheRead + m.tokens.cacheRead,
                cacheWrite = acc.cacheWrite + m.tokens.cacheWrite,
            )
        }
        val usd = assistants.sumOf { it.cost.usd }
        val providerId = container.providers.default!!.id
        return buildString {
            appendLine(
                "${Styles.accent("project")}=${projectId.value.take(8)} " +
                    "${Styles.accent("session")}=${sessionId.value.take(8)} " +
                    "${Styles.meta("·")} $title",
            )
            appendLine(
                "${Styles.accent("model")}=$providerId/$modelId " +
                    "${Styles.meta("·")} $turns turn(s) " +
                    "${Styles.meta("·")} in=${tokens.input} out=${tokens.output} " +
                    "${Styles.meta("·")} usd=${"%.5f".format(usd)}",
            )
        }.trimEnd()
    }

    private suspend fun sessionsTable(projectId: ProjectId, current: SessionId): String {
        val sessions = container.sessions.listSessions(projectId)
            .filter { !it.archived }
            .sortedByDescending { it.updatedAt }
        if (sessions.isEmpty()) return Styles.meta("no sessions in this project")
        return buildString {
            appendLine(Styles.meta("sessions (most recent first):"))
            sessions.forEach { s ->
                val isCurrent = s.id == current
                val marker = if (isCurrent) Styles.ok("*") else " "
                val id = if (isCurrent) Styles.accent(s.id.value.take(12)) else Styles.meta(s.id.value.take(12))
                appendLine("  $marker $id  ${Styles.meta(s.updatedAt.toString())}  ${s.title}")
            }
        }.trimEnd()
    }

    /**
     * One-line per-turn token / cache summary printed after the assistant finishes.
     * `input` is always the total input (subsumes cacheRead / cacheWrite on every
     * provider since the unified normalisation), so `cacheRead / input` is the real
     * cache hit rate regardless of backend.
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

    /**
     * Current todo list for the session. Mirrors [io.talevia.core.tool.builtin.TodoWriteTool]'s
     * rendering so the on-screen view matches what the model sees in its tool output.
     * Open items are shown with colour; completed/cancelled dimmed.
     */
    private suspend fun todosSummary(sessionId: SessionId): String {
        val todos = container.sessions.currentTodos(sessionId)
        if (todos.isEmpty()) return Styles.meta("no todos yet in this session")
        val open = todos.count { it.status != TodoStatus.COMPLETED && it.status != TodoStatus.CANCELLED }
        return buildString {
            appendLine(Styles.meta("todos: $open open · ${todos.size} total"))
            todos.forEach { appendLine("  ${renderTodo(it)}") }
        }.trimEnd()
    }

    private fun renderTodo(t: TodoInfo): String {
        val marker = when (t.status) {
            TodoStatus.PENDING -> "[ ]"
            TodoStatus.IN_PROGRESS -> "[~]"
            TodoStatus.COMPLETED -> "[x]"
            TodoStatus.CANCELLED -> "[-]"
        }
        val priority = if (t.priority != io.talevia.core.session.TodoPriority.MEDIUM) {
            Styles.meta(" (${t.priority.name.lowercase()})")
        } else {
            ""
        }
        val row = "$marker ${t.content}$priority"
        return when (t.status) {
            TodoStatus.IN_PROGRESS -> Styles.accent(row)
            TodoStatus.COMPLETED, TodoStatus.CANCELLED -> Styles.meta(row)
            TodoStatus.PENDING -> row
        }
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
