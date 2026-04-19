package io.talevia.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Compose Desktop entry point.
 *
 * M2 scope: a wiring demo, not a finished editor. Three panels — assets / timeline /
 * chat — show the round-trip from "register tool" to "tool dispatch" to "store
 * mutation" without yet invoking the LLM (provider keys aren't in scope here).
 */
@OptIn(ExperimentalUuidApi::class)
fun main() = application {
    val container = remember { AppContainer(desktopEnvWithDefaults()) }
    // Holder for window-level keyboard shortcuts. AppRoot mutates the action
    // fields from its composition scope (where the export / snapshot state is
    // in scope); the Window's onKeyEvent consults the holder on every key.
    val shortcuts = remember { DesktopShortcutHolder() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Talevia",
        state = rememberWindowState(width = 1260.dp, height = 820.dp),
        onKeyEvent = { shortcuts.handle(it) },
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(container = container, shortcuts = shortcuts)
            }
        }
    }
}

/**
 * Window-level keyboard shortcut holder. AppRoot registers callbacks via
 * `install(...)`; Window's `onKeyEvent` invokes `handle(event)` which
 * dispatches to the current callback when the binding matches. Keep the
 * surface small — adding a shortcut = one field + one case in `handle`.
 *
 * Bindings:
 *   cmd+E / ctrl+E — Export
 *   cmd+S / ctrl+S — Save snapshot
 *   cmd+R / ctrl+R — Regenerate stale clips for the active project
 */
private class DesktopShortcutHolder {
    var export: () -> Unit = {}
    var saveSnapshot: () -> Unit = {}
    var regenerateStale: () -> Unit = {}

    fun install(
        export: () -> Unit,
        saveSnapshot: () -> Unit,
        regenerateStale: () -> Unit,
    ) {
        this.export = export
        this.saveSnapshot = saveSnapshot
        this.regenerateStale = regenerateStale
    }

    fun handle(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val cmdOrCtrl = event.isMetaPressed || event.isCtrlPressed
        if (!cmdOrCtrl) return false
        return when (event.key) {
            androidx.compose.ui.input.key.Key.E -> { export(); true }
            androidx.compose.ui.input.key.Key.S -> { saveSnapshot(); true }
            androidx.compose.ui.input.key.Key.R -> { regenerateStale(); true }
            else -> false
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun AppRoot(container: AppContainer, shortcuts: DesktopShortcutHolder) {
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val assets = remember { mutableStateListOf<String>() }
    var renderProgress by remember { mutableStateOf<Float?>(null) }
    var renderMessage by remember { mutableStateOf<String?>(null) }
    var importPath by remember { mutableStateOf("") }
    var exportPath by remember { mutableStateOf(System.getProperty("user.home") + "/talevia-export.mp4") }
    // Resolution override for the Export button. "Project" means "fall through to the
    // project's OutputProfile", otherwise we pass explicit width/height to ExportTool.
    var exportResolution by remember { mutableStateOf(ResolutionPreset.Project) }
    var exportFps by remember { mutableStateOf(FpsPreset.Project) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    // Seek request plumbing: TimelinePanel (and any future caller) sets
    // `seekTargetSeconds` and bumps `seekSeq`; VideoPreviewPanel's LaunchedEffect
    // observes the pair and forwards to the JavaFX controller. Counter is
    // monotonic so identical-target clicks still fire.
    var seekTargetSeconds by remember { mutableStateOf(0.0) }
    var seekSeq by remember { mutableStateOf(0L) }

    // Mutable active project id — `ProjectBar` flips this when the user
    // switches / creates / forks / deletes. Defaults to a sentinel until
    // the bootstrap coroutine decides (existing project or new one).
    var projectId by remember { mutableStateOf(ProjectId("")) }
    var bootstrapped by remember { mutableStateOf(false) }

    // Bootstrap: prefer most-recently-updated existing project; otherwise
    // create one. Persistent SQLite (task 1) means this picks up the
    // user's previous session on relaunch.
    LaunchedEffect(Unit) {
        val summaries = container.projects.listSummaries()
        val picked = summaries.maxByOrNull { it.updatedAtEpochMs }
        projectId = if (picked != null) {
            ProjectId(picked.id)
        } else {
            val fresh = ProjectId(Uuid.random().toString())
            container.projects.upsert(
                "Untitled",
                Project(id = fresh, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
            )
            fresh
        }
        log += "ready · project=${projectId.value}"
        bootstrapped = true
    }

    // Subscribe to render-progress events + auto-refresh the preview when any
    // `export` tool call completes (whether from the Export button, a chat
    // turn, or a future toolbar shortcut). Without this the preview is
    // frozen at whatever was last loaded via the button — a chat-driven
    // export would silently succeed but the user would still see the stale
    // preview.
    LaunchedEffect(Unit) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect { ev ->
            when (val p = ev.part) {
                is Part.RenderProgress -> {
                    renderProgress = p.ratio
                    renderMessage = p.message
                    if (p.message != null) log += "render · ${"%.0f".format(p.ratio * 100)}% ${p.message}"
                    if (p.ratio >= 1f) {
                        scope.launch {
                            delay(1_200)
                            renderProgress = null
                            renderMessage = null
                        }
                    }
                }
                is Part.Tool -> {
                    val state = p.state
                    if (p.toolId == "export" && state is io.talevia.core.session.ToolState.Completed) {
                        val path = runCatching {
                            state.data.jsonObject["outputPath"]?.jsonPrimitive?.content
                        }.getOrNull()
                        if (!path.isNullOrBlank() && previewPath != path) {
                            previewPath = path
                            log += "preview → ${path.substringAfterLast('/')}"
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // Named lambdas for the major actions so both buttons + keyboard shortcuts
    // reach the same dispatch path (no drift between click and shortcut).
    val runExport: () -> Unit = {
        val path = exportPath
        if (path.isNotBlank()) {
            scope.launch {
                runCatching {
                    renderProgress = 0f
                    container.tools["export"]!!.dispatch(
                        buildJsonObject {
                            put("projectId", projectId.value)
                            put("outputPath", path)
                            exportResolution.width?.let { put("width", it) }
                            exportResolution.height?.let { put("height", it) }
                            exportFps.value?.let { put("frameRate", it) }
                        },
                        container.uiToolContext(projectId),
                    )
                    log += "render done → $path"
                    previewPath = path
                }.onFailure { log += "export failed: ${friendly(it)}"; renderProgress = null }
            }
        }
    }
    val runSaveSnapshot: () -> Unit = {
        scope.launch {
            runCatching {
                container.tools["save_project_snapshot"]!!.dispatch(
                    buildJsonObject { put("projectId", projectId.value) },
                    container.uiToolContext(projectId),
                )
                log += "saved snapshot"
            }.onFailure { log += "save snapshot failed: ${friendly(it)}" }
        }
    }
    val runRegenerateStale: () -> Unit = {
        scope.launch {
            runCatching {
                val result = container.tools["regenerate_stale_clips"]!!.dispatch(
                    buildJsonObject { put("projectId", projectId.value) },
                    container.uiToolContext(projectId),
                )
                log += "regenerate → ${result.outputForLlm}"
            }.onFailure { log += "regenerate failed: ${friendly(it)}" }
        }
    }

    // Keep the shortcut holder in sync with the current bootstrapped state —
    // shortcuts are no-ops until the project is loaded.
    LaunchedEffect(projectId, bootstrapped) {
        if (bootstrapped) {
            shortcuts.install(
                export = runExport,
                saveSnapshot = runSaveSnapshot,
                regenerateStale = runRegenerateStale,
            )
        }
    }

    PermissionDialog(container = container) { log += it }

    if (!bootstrapped) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProjectBar(
            container = container,
            activeProjectId = projectId,
            onProjectChange = { projectId = it },
            log = log,
        )
        Divider()
        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // ── Left: assets + actions ────────────────────────────────────────────
            Column(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                SectionTitle("Assets")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = importPath,
                        onValueChange = { importPath = it },
                        label = { Text("Import path") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val picked = container.filePicker.pick(
                                        filter = io.talevia.core.platform.FileFilter.Any,
                                        title = "Choose a file to import",
                                    )
                                    if (picked is MediaSource.File) importPath = picked.path
                                }.onFailure { log += "browse failed: ${friendly(it)}" }
                            }
                        },
                    ) { Text("Browse…") }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val path = importPath
                        if (path.isBlank()) return@Button
                        scope.launch {
                            runCatching {
                                val asset = container.media.import(MediaSource.File(path)) { container.engine.probe(it) }
                                assets += "${asset.id.value}  ·  ${"%.1f".format(asset.metadata.duration.inWholeMilliseconds / 1000.0)}s"
                                log += "imported ${asset.id.value}"
                                importPath = ""
                            }.onFailure { log += "import failed: ${friendly(it)}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import") }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    items(assets) { line ->
                        Text(line, modifier = Modifier.padding(vertical = 2.dp), fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            // ── Centre: timeline + render ────────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)) {
                SectionTitle("Timeline")
                Button(
                    onClick = {
                        if (assets.isEmpty()) {
                            log += "no assets to add"
                            return@Button
                        }
                        val nextAssetId = assets.last().substringBefore("  ·  ")
                        scope.launch {
                            runCatching {
                                container.tools["add_clip"]!!.dispatch(
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("assetId", nextAssetId)
                                    },
                                    container.uiToolContext(projectId),
                                )
                                log += "added clip"
                            }.onFailure { log += "add_clip failed: ${friendly(it)}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add last asset to timeline") }
                Spacer(Modifier.height(6.dp))
                TimelinePanel(
                    container = container,
                    projectId = projectId,
                    log = log,
                    onSeekPreview = { seconds ->
                        seekTargetSeconds = seconds
                        seekSeq += 1L
                    },
                )

                Spacer(Modifier.height(12.dp))
                SectionTitle("Render")
                OutlinedTextField(
                    value = exportPath,
                    onValueChange = { exportPath = it },
                    label = { Text("Export path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResolutionDropdown(
                        selected = exportResolution,
                        onSelect = { exportResolution = it },
                    )
                    Spacer(Modifier.width(6.dp))
                    FpsDropdown(
                        selected = exportFps,
                        onSelect = { exportFps = it },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = runExport,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export  (⌘E)") }
                Spacer(Modifier.height(6.dp))
                renderProgress?.let { ratio ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${"%.0f".format(ratio * 100)}%",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            renderMessage?.let { msg ->
                                Text(
                                    text = "  ·  $msg",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF555555),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(12.dp))
                VideoPreviewPanel(
                    filePath = previewPath,
                    modifier = Modifier.fillMaxWidth(),
                    seekRequest = if (seekSeq > 0L) seekSeq to seekTargetSeconds else null,
                )
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            // ── Right: tabbed workbench (chat / source) + activity log ───────────
            Column(modifier = Modifier.width(460.dp).fillMaxHeight().padding(start = 12.dp)) {
                var tab by remember { mutableStateOf(RightTab.Chat) }
                androidx.compose.material3.TabRow(selectedTabIndex = tab.ordinal) {
                    RightTab.entries.forEach { entry ->
                        androidx.compose.material3.Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                when (tab) {
                    RightTab.Chat -> ChatPanel(container = container, projectId = projectId, log = log)
                    RightTab.Source -> SourcePanel(container = container, projectId = projectId, log = log)
                    RightTab.Snapshots -> SnapshotPanel(container = container, projectId = projectId, log = log)
                    RightTab.Lockfile -> LockfilePanel(container = container, projectId = projectId, log = log)
                }
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(6.dp))
                SectionTitle("Activity")
                LazyColumn(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    items(log) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}

/**
 * The real chat pane — drives `Agent.run` against the user-typed prompt, then
 * streams message/part updates from the bus so the user sees tool calls and
 * text deltas as they happen. Falls back to a helpful placeholder when no
 * provider API key is set in the environment.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
private fun ChatPanel(container: AppContainer, projectId: ProjectId, log: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
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
                // Replay stored parts into the visible chatLines.
                runCatching {
                    container.sessions.listSessionParts(existing.id, includeCompacted = false)
                        .forEach { p ->
                            when (p) {
                                is Part.Text -> chatLines += ChatLine("assistant", p.text.take(400))
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
                        is Part.Text -> ChatLine("assistant", p.text.take(400))
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
                                            container.sessions.listSessionParts(s.id, includeCompacted = false)
                                                .forEach { p ->
                                                    when (p) {
                                                        is Part.Text -> chatLines += ChatLine("assistant", p.text.take(400))
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
    val costLabel = if (sessionUsageIn == 0L && sessionUsageOut == 0L) "" else {
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

private fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-4o"
    else -> "default"
}

/**
 * Export resolution preset. `Project` means "use the project's OutputProfile"
 * (no override); concrete presets force width/height on the Export tool
 * input. `HD720` intentionally names the resolution, not a vendor label.
 */
private enum class ResolutionPreset(val label: String, val width: Int?, val height: Int?) {
    Project("Project", null, null),
    HD720("720p", 1280, 720),
    FullHD("1080p", 1920, 1080),
    UHD4K("4K", 3840, 2160),
}

private enum class FpsPreset(val label: String, val value: Int?) {
    Project("Project fps", null),
    Fps24("24", 24),
    Fps30("30", 30),
    Fps60("60", 60),
}

@Composable
private fun ResolutionDropdown(selected: ResolutionPreset, onSelect: (ResolutionPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.TextButton(onClick = { open = true }) {
            Text("${selected.label} ▾", fontFamily = FontFamily.Monospace)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ResolutionPreset.entries.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

@Composable
private fun FpsDropdown(selected: FpsPreset, onSelect: (FpsPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.TextButton(onClick = { open = true }) {
            Text("${selected.label} ▾", fontFamily = FontFamily.Monospace)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FpsPreset.entries.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = { onSelect(p); open = false },
                )
            }
        }
    }
}

/**
 * Best-effort extraction of an openable filesystem path from a tool's result
 * JSON. Looks first for `outputPath` (ExportTool produces this), then for
 * asset-id fields (generate_image / _video / _music / extract_frame /
 * upscale_asset / synthesize_speech) and resolves them via MediaStorage.
 * Returns null when the tool output has no natural file artefact (e.g.
 * `apply_filter`, `add_clip`, `define_character_ref`).
 */
private suspend fun resolveOpenablePath(
    container: AppContainer,
    data: kotlinx.serialization.json.JsonElement,
): String? {
    val obj = (data as? kotlinx.serialization.json.JsonObject) ?: return null
    obj["outputPath"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.let { p -> return p } }
    val assetKeys = listOf("upscaledAssetId", "frameAssetId", "assetId", "newAssetId")
    for (key in assetKeys) {
        val idStr = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull() ?: continue
        runCatching {
            return container.media.resolve(io.talevia.core.AssetId(idStr))
        }
    }
    return null
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (isString || !content.contains('"')) content.takeIf { it.isNotBlank() } else null

private fun openExternallyIfExists(path: String) {
    runCatching {
        val file = java.io.File(path)
        if (file.exists() && java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().open(file)
        }
    }
}

private enum class RightTab(val label: String) {
    Chat("Chat"),
    Source("Source"),
    Snapshots("Snapshots"),
    Lockfile("Lockfile"),
}

/**
 * Real desktop env, plus defaults for anything the user didn't configure.
 *
 * - `TALEVIA_DB_PATH` defaults to `~/.talevia/talevia.db` so projects /
 *   sessions / source DAGs / snapshots survive app restarts out-of-box.
 *   Set `TALEVIA_DB_PATH=:memory:` to opt back into ephemeral mode.
 * - `TALEVIA_MEDIA_DIR` defaults to `~/.talevia/media` so AIGC blobs and
 *   the asset catalog land in a predictable, persistent spot — without
 *   this the catalog was in-memory and AssetIds referenced by saved
 *   projects broke on restart.
 *
 * Only fills in defaults the user didn't already set. Anything the user
 * passed via the environment wins.
 */
private fun desktopEnvWithDefaults(): Map<String, String> {
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
}
