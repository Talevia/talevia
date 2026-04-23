package io.talevia.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.BusEvent
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.ToolState
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The real chat pane — drives `Agent.run` against the user-typed prompt, then
 * streams message/part updates from the bus so the user sees tool calls and
 * text deltas as they happen. Falls back to a helpful placeholder when no
 * provider API key is set in the environment.
 *
 * Split out of `Main.kt` as part of `debt-split-desktop-main-kt`; the
 * function is `internal` so only [AppRoot] sees it.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun ChatPanel(container: AppContainer, projectId: ProjectId, log: SnapshotStateList<String>) {
    val scope = rememberCoroutineScope()
    val hasProvider = remember { container.providers.default != null }
    if (!hasProvider) {
        SectionTitle("Chat")
        Text(
            "No provider API key set. Export ANTHROPIC_API_KEY or OPENAI_API_KEY and relaunch to enable the agent loop.",
            modifier = Modifier.padding(vertical = 6.dp),
        )
        return
    }

    // Session restore: look up the most-recent session for this project and
    // reuse it so chat history survives app restarts. Create a fresh one only
    // when the project has no prior sessions. Keyed on projectId so switching
    // projects swaps the thread (each project keeps its own chat stream).
    val sessionId = remember(projectId) {
        androidx.compose.runtime.mutableStateOf(SessionId(Uuid.random().toString()))
    }
    val sessionBootstrapped = remember(projectId) { androidx.compose.runtime.mutableStateOf(false) }
    val chatLines = remember(projectId) { mutableStateListOf<ChatLine>() }
    var prompt by remember(projectId) { mutableStateOf("") }
    var busy by remember(projectId) { mutableStateOf(false) }
    var sessionUsageIn by remember(projectId) { mutableStateOf(0L) }
    var sessionUsageOut by remember(projectId) { mutableStateOf(0L) }
    var sessionCostUsd by remember(projectId) { mutableStateOf(0.0) }

    // Bootstrap: prefer the most-recent persisted session for this project;
    // otherwise mint a fresh one and persist it so Agent.run has a row to
    // append to. Also replays stored parts so the visible history survives
    // restarts.
    remember(projectId) {
        scope.launch {
            val existing = container.sessions.listSessions(projectId)
                .filter { !it.archived }
                .maxByOrNull { it.updatedAt }
            if (existing != null) {
                sessionId.value = existing.id
                // Replay stored parts into the visible chatLines. Using
                // listMessagesWithParts (not listSessionParts) is load-bearing:
                // we need the parent Message to label a Part.Text as "you"
                // vs "assistant" — otherwise a user prompt shows up as an
                // assistant echo and looks like the AI is parroting the user.
                runCatching {
                    container.sessions.listMessagesWithParts(existing.id, includeCompacted = false)
                        .forEach { mwp -> replayMessageParts(container, mwp, chatLines) }
                }
                sessionBootstrapped.value = true
                log += "resumed session ${existing.id.value.take(8)} · ${chatLines.size} line(s)"
            } else {
                container.sessions.createSession(
                    Session(
                        id = sessionId.value,
                        projectId = projectId,
                        title = "Chat",
                        createdAt = Clock.System.now(),
                        updatedAt = Clock.System.now(),
                    ),
                )
                sessionBootstrapped.value = true
            }
        }
    }

    // Subscribe to part updates for this session and render them as chat lines.
    // For tool completions we also try to extract an openable file path so
    // the user can jump straight to the artefact (export / generate_image /
    // extract_frame / upscale_asset / auto_subtitle_clip — anything that
    // produces an assetId or an outputPath).
    remember {
        scope.launch {
            container.bus.subscribe<BusEvent.PartUpdated>()
                .filterIsInstance<BusEvent.PartUpdated>()
                .collect { ev ->
                    if (ev.sessionId != sessionId.value) return@collect
                    val line: ChatLine? = when (val p = ev.part) {
                        is Part.Text -> {
                            // The agent persists the user prompt as a Part.Text on
                            // the user message; we already rendered that locally
                            // when Send was clicked, so drop it to avoid echoing
                            // the prompt back as an "assistant" line.
                            val parent = runCatching { container.sessions.getMessage(ev.messageId) }.getOrNull()
                            if (parent is Message.Assistant) ChatLine("assistant", p.text.take(400)) else null
                        }
                        is Part.Tool -> {
                            val state = p.state
                            val path = if (state is io.talevia.core.session.ToolState.Completed) {
                                runCatching { resolveOpenablePath(container, state.data) }.getOrNull()
                            } else {
                                null
                            }
                            ChatLine(
                                role = "tool/${p.toolId}",
                                text = "→ ${p.state::class.simpleName}" + (path?.let { " · $it" } ?: ""),
                                openPath = path,
                            )
                        }
                        is Part.RenderProgress -> null  // already surfaced in centre panel
                        is Part.TimelineSnapshot -> null
                        else -> null
                    }
                    if (line != null) chatLines += line
                }
        }
    }

    // Session switcher state: mirrors sessions.listSessions(projectId) with a
    // live reload on bus events so a new agent run (which upserts a title)
    // shows up in the menu without a manual refresh.
    val projectSessions = remember(projectId) { mutableStateListOf<io.talevia.core.session.Session>() }
    var sessionMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        val loaded = runCatching { container.sessions.listSessions(projectId).filter { !it.archived } }
            .getOrElse { emptyList() }
        projectSessions.clear(); projectSessions.addAll(loaded.sortedByDescending { it.updatedAt })
    }
    LaunchedEffect(projectId) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect {
            val loaded = runCatching { container.sessions.listSessions(projectId).filter { !it.archived } }
                .getOrElse { emptyList() }
            projectSessions.clear(); projectSessions.addAll(loaded.sortedByDescending { it.updatedAt })
        }
    }

    // Recompute session token/cost totals whenever the active session or any of its
    // messages change. Keyed on sessionId.value so switching sessions resets the counters.
    LaunchedEffect(sessionId.value) {
        fun recompute(msgs: List<Message>) {
            val asstMsgs = msgs.filterIsInstance<Message.Assistant>()
            sessionUsageIn = asstMsgs.sumOf { it.tokens.input }
            sessionUsageOut = asstMsgs.sumOf { it.tokens.output }
            sessionCostUsd = asstMsgs.sumOf { it.cost.usd }
        }
        runCatching { recompute(container.sessions.listMessages(sessionId.value)) }
        container.bus.subscribe<BusEvent.MessageUpdated>().collect { ev ->
            if (ev.sessionId == sessionId.value) {
                runCatching { recompute(container.sessions.listMessages(sessionId.value)) }
            }
        }
    }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        SectionTitle("Chat")
        Spacer(Modifier.weight(1f))
        androidx.compose.foundation.layout.Box {
            val active = projectSessions.firstOrNull { it.id == sessionId.value }
            androidx.compose.material3.TextButton(onClick = { sessionMenuOpen = true }) {
                val label = active?.title?.take(30) ?: sessionId.value.value.take(8)
                Text("$label ▾", fontFamily = FontFamily.Monospace)
            }
            androidx.compose.material3.DropdownMenu(
                expanded = sessionMenuOpen,
                onDismissRequest = { sessionMenuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("+ New chat") },
                    onClick = {
                        sessionMenuOpen = false
                        scope.launch {
                            val fresh = SessionId(Uuid.random().toString())
                            container.sessions.createSession(
                                Session(
                                    id = fresh,
                                    projectId = projectId,
                                    title = "Chat",
                                    createdAt = Clock.System.now(),
                                    updatedAt = Clock.System.now(),
                                ),
                            )
                            sessionId.value = fresh
                            chatLines.clear()
                            log += "new chat ${fresh.value.take(8)}"
                        }
                    },
                )
                if (projectSessions.isNotEmpty()) {
                    androidx.compose.material3.HorizontalDivider()
                    projectSessions.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                val mark = if (s.id == sessionId.value) "• " else "  "
                                Text("$mark${s.title.take(32)}", fontFamily = FontFamily.Monospace)
                            },
                            onClick = {
                                sessionMenuOpen = false
                                if (s.id != sessionId.value) {
                                    sessionId.value = s.id
                                    chatLines.clear()
                                    scope.launch {
                                        runCatching {
                                            container.sessions.listMessagesWithParts(s.id, includeCompacted = false)
                                                .forEach { mwp -> replayMessageParts(container, mwp, chatLines) }
                                        }
                                        log += "resumed session ${s.id.value.take(8)} · ${chatLines.size} line(s)"
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        items(chatLines) { line ->
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("${line.role}: ", fontFamily = FontFamily.Monospace, color = Color(0xFF3B5BA9))
                Text(line.text, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                if (line.openPath != null) {
                    androidx.compose.material3.TextButton(
                        onClick = { openExternallyIfExists(line.openPath) },
                    ) { Text("Open") }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        label = { Text("Ask the agent") },
        modifier = Modifier.fillMaxWidth(),
        enabled = sessionBootstrapped.value && !busy,
    )
    Spacer(Modifier.height(6.dp))
    Button(
        enabled = sessionBootstrapped.value && !busy && prompt.isNotBlank(),
        onClick = {
            val text = prompt
            prompt = ""
            chatLines += ChatLine("you", text)
            val agent = container.newAgent()
            if (agent == null) {
                log += "chat: no provider configured"
                return@Button
            }
            busy = true
            scope.launch {
                val provider = container.providers.default!!
                runCatching {
                    agent.run(
                        RunInput(
                            sessionId = sessionId.value,
                            text = text,
                            model = ModelRef(provider.id, defaultModelFor(provider.id)),
                            permissionRules = container.permissionRules.toList(),
                        ),
                    )
                }.onFailure { t -> log += "agent failed: ${friendly(t)}" }
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Thinking…" else "Send") }
    Spacer(Modifier.height(4.dp))
    val costLabel = if (sessionUsageIn == 0L && sessionUsageOut == 0L) {
        ""
    } else {
        val costStr = if (sessionCostUsd == 0.0) "" else " · \$${"%.5f".format(sessionCostUsd)}"
        "${sessionUsageIn} in · ${sessionUsageOut} out$costStr"
    }
    if (costLabel.isNotEmpty()) {
        Text(
            costLabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class ChatLine(
    val role: String,
    val text: String,
    /** Optional absolute filesystem path to an artefact the user can open from the row. */
    val openPath: String? = null,
)

/**
 * Replay one persisted message into [chatLines]. Labels Part.Text by the
 * owning Message role — a User message's Part.Text is "you", an Assistant's
 * is "assistant". (A previous implementation read parts without their parent
 * message and wound up labelling every stored prompt as "assistant", which
 * looked like the AI was echoing the user.)
 */
private suspend fun replayMessageParts(
    container: AppContainer,
    mwp: io.talevia.core.session.MessageWithParts,
    chatLines: androidx.compose.runtime.snapshots.SnapshotStateList<ChatLine>,
) {
    val role = when (mwp.message) {
        is Message.User -> "you"
        is Message.Assistant -> "assistant"
    }
    for (p in mwp.parts) when (p) {
        is Part.Text -> chatLines += ChatLine(role, p.text.take(400))
        is Part.Tool -> {
            val st = p.state
            val path = if (st is io.talevia.core.session.ToolState.Completed) {
                runCatching { resolveOpenablePath(container, st.data) }.getOrNull()
            } else {
                null
            }
            chatLines += ChatLine(
                role = "tool/${p.toolId}",
                text = "→ ${p.state::class.simpleName}" + (path?.let { " · $it" } ?: ""),
                openPath = path,
            )
        }
        else -> {}
    }
}
