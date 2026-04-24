package io.talevia.cli.repl

import io.talevia.cli.CliContainer
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SessionRevert
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.currentTodos
import kotlinx.datetime.Clock
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the `/slash command` surface for the REPL — parsing, dispatching,
 * and formatting the per-command output. Split out of `Repl.kt` as part
 * of `debt-split-cli-repl` (2026-04-23).
 *
 * One [handle] call per user line that starts with `/`. Returns an
 * [Outcome] the caller uses to decide whether to keep the REPL loop
 * alive (most commands) or terminate it (`/exit`, `/quit`).
 *
 * Callers pass mutable state (active session, active model) as read +
 * write handles so this class stays stateless; the REPL retains the
 * loop's vars.
 */
internal class SlashCommandDispatcher(
    private val container: CliContainer,
    private val terminal: Terminal,
    private val renderer: Renderer,
) {
    enum class Outcome { CONTINUE, EXIT }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun handle(
        raw: String,
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
            "spend" -> renderer.println(spendSummary(currentSession))
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
     * `/fork` with no arg duplicates the current session whole. With a
     * prefix, truncates the branch at that anchor — everything after it
     * in the parent stays in the parent, so the branched session is a
     * clean continuation point. Switches the REPL into the new branch on
     * success (like `/resume`) because the natural UX after typing
     * `/fork` is "now keep editing in the branch I just made".
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
     * List recent turns as candidate anchors for `/revert`. We show
     * every message (user + assistant) because the simplest mental
     * model is "pick the line you want to keep, everything after it is
     * dropped". Message ids are surfaced as a 12-char prefix — same
     * affordance as `/resume` — enough for uniqueness in any realistic
     * session length.
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
     * `/revert <prefix>` drives [SessionRevert]. Only 1 match allowed —
     * picking the wrong anchor silently would blow away the wrong N
     * turns. An empty prefix prints usage rather than reverting to the
     * most recent turn (which would be a no-op but also looks like a
     * footgun).
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
     * Single-line `/status` summary. Answers "which session am I in,
     * which model is queued for the next turn, how much have we spent
     * so far" without flipping through `/sessions`, `/model`, and
     * `/cost`.
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
     * Current todo list for the session. Mirrors
     * [io.talevia.core.tool.builtin.TodoWriteTool]'s rendering so the
     * on-screen view matches what the model sees in its tool output.
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

    /**
     * `/spend` surfaces `session_query(select=spend_summary)` through a
     * CLI shortcut — the AIGC dollar roll-up is different from `/cost`'s
     * LLM-token totals. Same data an agent would see via the tool; the
     * slash form saves the user from hand-typing
     * `session_query(select=spend_summary, sessionId=...)` every time
     * they want to answer "how much am I burning on image gen this
     * session?".
     *
     * Implementation: construct a fresh `SessionQueryTool` wired to the
     * container's stores and decode the single-row `SessionSpendSummaryRow`
     * from the output. Zero new schema — we reuse the existing query
     * handler so the CLI view can't drift from what the agent sees.
     */
    private suspend fun spendSummary(sessionId: SessionId): String {
        val tool = io.talevia.core.tool.builtin.session.SessionQueryTool(
            sessions = container.sessions,
            agentStates = container.agentStates,
            projects = container.projects,
        )
        val ctx = io.talevia.core.tool.ToolContext(
            sessionId = sessionId,
            messageId = io.talevia.core.MessageId("slash-spend"),
            callId = io.talevia.core.CallId("slash-spend"),
            askPermission = { io.talevia.core.permission.PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        val out = runCatching {
            tool.execute(
                io.talevia.core.tool.builtin.session.SessionQueryTool.Input(
                    select = io.talevia.core.tool.builtin.session.SessionQueryTool.SELECT_SPEND_SUMMARY,
                    sessionId = sessionId.value,
                ),
                ctx,
            )
        }.getOrElse { e ->
            return Styles.meta("/spend: ${e.message ?: e::class.simpleName}")
        }
        val row = runCatching {
            io.talevia.core.JsonConfig.default.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(
                    io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow.serializer(),
                ),
                out.data.rows,
            ).single()
        }.getOrElse { e ->
            return Styles.meta("/spend: failed to decode row — ${e.message}")
        }

        return formatSpendSummary(row)
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
}
