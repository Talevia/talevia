package io.talevia.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.platform.FileFilter
import io.talevia.core.session.Part
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Root composable of the Compose Desktop app — the three-column layout
 * (assets / timeline+render / tabbed workbench) plus activity log.
 * Owns the bootstrap LaunchedEffects that pick / create the active
 * project and subscribe to render progress.
 *
 * Split out of `Main.kt` as part of `debt-split-desktop-main-kt`; the
 * function is `internal` so only [main] sees it.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun AppRoot(container: AppContainer, shortcuts: DesktopShortcutHolder) {
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    val assets = remember { mutableStateListOf<String>() }
    var renderProgress by remember { mutableStateOf<Float?>(null) }
    var renderMessage by remember { mutableStateOf<String?>(null) }
    // Mid-render preview: populated from Part.RenderProgress.thumbnailPath when
    // the engine emits Preview events (ffmpeg -progress stream carries these
    // for the whole-timeline path). Re-read on every update — the engine
    // overwrites the same file between ticks, so we need the fresh bytes.
    // Cleared when ratio hits 1.0 alongside renderProgress so the expert-path
    // preview doesn't linger over the final file the VideoPreviewPanel loads.
    var renderThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
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
                    p.thumbnailPath?.let { path ->
                        // Best-effort: if the file is mid-rotation or the engine
                        // already cleaned it up (e.g. on the final completion
                        // tick), silently skip — we only lose one preview
                        // frame, not the progress bar itself.
                        runCatching {
                            val bytes = java.io.File(path).readBytes()
                            renderThumbnail = org.jetbrains.skia.Image
                                .makeFromEncoded(bytes)
                                .toComposeImageBitmap()
                        }
                    }
                    if (p.ratio >= 1f) {
                        scope.launch {
                            delay(1_200)
                            renderProgress = null
                            renderMessage = null
                            renderThumbnail = null
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
                container.tools["project_snapshot_action"]!!.dispatch(
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("action", "save")
                    },
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
                        if (projectId.value.isBlank()) {
                            log += "import failed: no project selected"
                            return@Button
                        }
                        scope.launch {
                            runCatching {
                                val metadata = container.engine.probe(MediaSource.File(path))
                                @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                                val newId = io.talevia.core.AssetId(kotlin.uuid.Uuid.random().toString())
                                val asset = io.talevia.core.domain.MediaAsset(
                                    id = newId,
                                    source = MediaSource.File(path),
                                    metadata = metadata,
                                )
                                container.projects.mutate(projectId) { p ->
                                    p.copy(assets = p.assets + asset)
                                }
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
                                container.tools["clip_action"]!!.dispatch(
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("action", "add")
                                        putJsonArray("addItems") {
                                            addJsonObject { put("assetId", nextAssetId) }
                                        }
                                    },
                                    container.uiToolContext(projectId),
                                )
                                log += "added clip"
                            }.onFailure { log += "clip_action(action=add) failed: ${friendly(it)}" }
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
                        renderThumbnail?.let { bmp ->
                            Spacer(Modifier.height(6.dp))
                            Image(
                                bitmap = bmp,
                                contentDescription = "mid-render preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)),
                            )
                        }
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
