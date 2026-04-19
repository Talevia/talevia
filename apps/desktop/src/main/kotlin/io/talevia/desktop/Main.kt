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
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
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

    Window(
        onCloseRequest = ::exitApplication,
        title = "Talevia",
        state = rememberWindowState(width = 1260.dp, height = 820.dp),
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppRoot(container = container)
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun AppRoot(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val assets = remember { mutableStateListOf<String>() }
    var renderProgress by remember { mutableStateOf<Float?>(null) }
    var importPath by remember { mutableStateOf("") }
    var exportPath by remember { mutableStateOf(System.getProperty("user.home") + "/talevia-export.mp4") }
    var previewPath by remember { mutableStateOf<String?>(null) }

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

    // Subscribe to render-progress events.
    LaunchedEffect(Unit) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect { ev ->
            when (val p = ev.part) {
                is Part.RenderProgress -> {
                    renderProgress = p.ratio
                    if (p.message != null) log += "render · ${"%.0f".format(p.ratio * 100)}% ${p.message}"
                }
                else -> {}
            }
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
                OutlinedTextField(
                    value = importPath,
                    onValueChange = { importPath = it },
                    label = { Text("Import path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                            }.onFailure { log += "import failed: ${it.message}" }
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
                            }.onFailure { log += "add_clip failed: ${it.message}" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add last asset to timeline") }
                Spacer(Modifier.height(6.dp))
                TimelinePanel(container = container, projectId = projectId, log = log)

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
                Button(
                    onClick = {
                        val path = exportPath
                        scope.launch {
                            runCatching {
                                renderProgress = 0f
                                container.tools["export"]!!.dispatch(
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("outputPath", path)
                                    },
                                    container.uiToolContext(projectId),
                                )
                                log += "render done → $path"
                                previewPath = path
                            }.onFailure { log += "export failed: ${it.message}"; renderProgress = null }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export") }
                Spacer(Modifier.height(6.dp))
                renderProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }

                Spacer(Modifier.height(12.dp))
                VideoPreviewPanel(filePath = previewPath, modifier = Modifier.fillMaxWidth())
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

    val sessionId = remember { SessionId(Uuid.random().toString()) }
    val sessionBootstrapped = remember { androidx.compose.runtime.mutableStateOf(false) }
    val chatLines = remember { mutableStateListOf<ChatLine>() }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Bootstrap: persist an empty Session so Agent.run has a row to append to.
    remember {
        scope.launch {
            container.sessions.createSession(
                Session(
                    id = sessionId,
                    projectId = projectId,
                    title = "Chat",
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                ),
            )
            sessionBootstrapped.value = true
        }
    }

    // Subscribe to part updates for this session and render them as chat lines.
    remember {
        scope.launch {
            container.bus.subscribe<BusEvent.PartUpdated>()
                .filterIsInstance<BusEvent.PartUpdated>()
                .collect { ev ->
                    if (ev.sessionId != sessionId) return@collect
                    val line = when (val p = ev.part) {
                        is Part.Text -> ChatLine("assistant", p.text.take(400))
                        is Part.Tool -> ChatLine("tool/${p.toolId}", "→ ${p.state::class.simpleName}")
                        is Part.RenderProgress -> null  // already surfaced in centre panel
                        is Part.TimelineSnapshot -> null
                        else -> null
                    }
                    if (line != null) chatLines += line
                }
        }
    }

    SectionTitle("Chat")
    LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        items(chatLines) { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text("${line.role}: ", fontFamily = FontFamily.Monospace, color = Color(0xFF3B5BA9))
                Text(line.text, fontFamily = FontFamily.Monospace)
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
                            sessionId = sessionId,
                            text = text,
                            model = ModelRef(provider.id, defaultModelFor(provider.id)),
                            permissionRules = container.permissionRules.toList(),
                        ),
                    )
                }.onFailure { t -> log += "agent failed: ${t.message}" }
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Thinking…" else "Send") }
}

private data class ChatLine(val role: String, val text: String)

private fun defaultModelFor(providerId: String): String = when (providerId) {
    "anthropic" -> "claude-opus-4-7"
    "openai" -> "gpt-4o"
    else -> "default"
}

private enum class RightTab(val label: String) {
    Chat("Chat"),
    Source("Source"),
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
