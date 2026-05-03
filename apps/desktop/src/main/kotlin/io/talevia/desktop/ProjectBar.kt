package io.talevia.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.ProjectSummary
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Top-of-window project bar — lets the user see the active project,
 * switch between stored projects, create / fork, save and restore
 * snapshots, or delete. Closes the VISION §3.4 "可版本化 / 可分支" gap
 * on the desktop expert surface.
 *
 * All mutations route through the existing project tools
 * (`create_project`, `fork_project`, `project_snapshot_action`,
 * `delete_project`). The bar doesn't
 * re-implement lifecycle — it's just a UI onto the tool registry.
 *
 * Refreshes the summary list on [BusEvent.PartUpdated] so any change
 * (agent-driven or panel-driven) shows up without a manual refresh.
 */
@Composable
fun ProjectBar(
    container: AppContainer,
    activeProjectId: ProjectId,
    onProjectChange: (ProjectId) -> Unit,
    log: SnapshotStateList<String>,
) {
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    val summaries = remember { mutableStateListOf<ProjectSummary>() }
    var active by remember(activeProjectId) { mutableStateOf<Project?>(null) }

    suspend fun refresh() {
        summaries.clear()
        summaries.addAll(container.projects.listSummaries())
        active = container.projects.get(activeProjectId)
    }

    LaunchedEffect(activeProjectId) { refresh() }
    LaunchedEffect(Unit) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect { refresh() }
    }

    fun dispatch(toolId: String, input: JsonObject, label: String, after: (suspend () -> Unit)? = null) {
        val tool = container.tools[toolId]
        if (tool == null) {
            log += "$toolId not registered"; return
        }
        scope.launch {
            runCatching {
                tool.dispatch(input, container.uiToolContext(activeProjectId))
                refresh()
                after?.invoke()
                log += "$label ✓"
            }.onFailure { log += "$label failed: ${friendly(it)}" }
        }
    }

    var showSnapshotsDialog by remember { mutableStateOf(false) }
    var showSwitchDialog by remember { mutableStateOf(false) }
    var snapshotLabel by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("Project", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        val summary = summaries.firstOrNull { it.id == activeProjectId.value }
        Text(
            text = summary?.title ?: "(untitled)",
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = activeProjectId.value.take(8),
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF888888),
        )
        val snapCount = active?.snapshots?.size ?: 0
        Text(
            text = "  ·  $snapCount snapshot${if (snapCount == 1) "" else "s"}",
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF888888),
        )
        Spacer(Modifier.width(12.dp))
        Box {
            TextButton(onClick = { menuExpanded = true }) { Text("Actions ▾") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("New project (blank)") },
                    onClick = {
                        menuExpanded = false
                        val title = "Untitled ${kotlinx.datetime.Clock.System.now()}".take(40)
                        dispatch(
                            "create_project",
                            buildJsonObject { put("title", title) },
                            "create project",
                            after = {
                                val latest = container.projects.listSummaries().maxByOrNull { it.createdAtEpochMs }
                                latest?.let { onProjectChange(ProjectId(it.id)) }
                            },
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("New narrative project…") },
                    onClick = {
                        menuExpanded = false
                        val title = "Narrative ${kotlinx.datetime.Clock.System.now()}".take(40)
                        dispatch(
                            "project_lifecycle_action",
                            buildJsonObject {
                                put("action", "create_from_template")
                                put("title", title)
                                put("template", "narrative")
                            },
                            "create narrative project",
                            after = {
                                val latest = container.projects.listSummaries().maxByOrNull { it.createdAtEpochMs }
                                latest?.let { onProjectChange(ProjectId(it.id)) }
                            },
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("New vlog project…") },
                    onClick = {
                        menuExpanded = false
                        val title = "Vlog ${kotlinx.datetime.Clock.System.now()}".take(40)
                        dispatch(
                            "project_lifecycle_action",
                            buildJsonObject {
                                put("action", "create_from_template")
                                put("title", title)
                                put("template", "vlog")
                            },
                            "create vlog project",
                            after = {
                                val latest = container.projects.listSummaries().maxByOrNull { it.createdAtEpochMs }
                                latest?.let { onProjectChange(ProjectId(it.id)) }
                            },
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Fork current") },
                    onClick = {
                        menuExpanded = false
                        val now = kotlinx.datetime.Clock.System.now()
                        dispatch(
                            "fork_project",
                            buildJsonObject {
                                put("sourceProjectId", activeProjectId.value)
                                put("newTitle", "Fork $now".take(40))
                            },
                            "fork project",
                            after = {
                                val latest = container.projects.listSummaries().maxByOrNull { it.createdAtEpochMs }
                                latest?.let { onProjectChange(ProjectId(it.id)) }
                            },
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Save snapshot…") },
                    onClick = {
                        menuExpanded = false
                        snapshotLabel = ""
                        showSnapshotsDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Switch project…") },
                    onClick = {
                        menuExpanded = false
                        showSwitchDialog = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete current") },
                    enabled = summaries.size > 1,
                    onClick = {
                        menuExpanded = false
                        val victim = activeProjectId.value
                        dispatch(
                            "delete_project",
                            buildJsonObject { put("projectId", victim) },
                            "delete project",
                            after = {
                                val next = container.projects.listSummaries().firstOrNull { it.id != victim }
                                if (next != null) onProjectChange(ProjectId(next.id))
                            },
                        )
                    },
                )
                androidx.compose.material3.HorizontalDivider()
                // OAuth-based ChatGPT login (provider id "openai-codex"). After
                // a successful sign-in, the credential file is written under
                // ~/.talevia/openai-codex-auth.json; the desktop must restart
                // to register the provider in the registry — no hot-reload
                // path today.
                DropdownMenuItem(
                    text = { Text("Sign in to ChatGPT (Codex)…") },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            runCatching {
                                val authenticator = io.talevia.core.provider.openai.codex.JvmOpenAiCodexAuthenticator(
                                    httpClient = container.httpClient,
                                    onPrompt = { url -> log += "Open in browser: $url" },
                                )
                                log += "Opening browser for ChatGPT sign-in…"
                                val creds = authenticator.login()
                                container.openAiCodexCredentials.save(creds)
                                log += "Signed in to openai-codex (account ${creds.accountId.take(6)}…). Restart Talevia to use this provider."
                            }.onFailure { log += "ChatGPT sign-in failed: ${friendly(it)}" }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sign out (Codex)") },
                    onClick = {
                        menuExpanded = false
                        scope.launch {
                            runCatching { container.openAiCodexCredentials.clear() }
                                .onSuccess { log += "Cleared openai-codex credentials. Restart to drop the provider." }
                                .onFailure { log += "Sign-out failed: ${friendly(it)}" }
                        }
                    },
                )
            }
        }
    }

    if (showSnapshotsDialog) {
        SnapshotsDialog(
            snapshots = active?.snapshots.orEmpty(),
            pendingLabel = snapshotLabel,
            onLabelChange = { snapshotLabel = it },
            onSave = {
                val label = snapshotLabel.takeIf { it.isNotBlank() }
                dispatch(
                    "project_snapshot_action",
                    buildJsonObject {
                        put("projectId", activeProjectId.value)
                        put("action", "save")
                        if (label != null) put("label", label)
                    },
                    "save snapshot",
                )
                snapshotLabel = ""
            },
            onRestore = { snapshotId ->
                dispatch(
                    "project_snapshot_action",
                    buildJsonObject {
                        put("projectId", activeProjectId.value)
                        put("action", "restore")
                        put("snapshotId", snapshotId)
                    },
                    "restore snapshot $snapshotId",
                )
            },
            onClose = { showSnapshotsDialog = false },
        )
    }

    if (showSwitchDialog) {
        SwitchProjectDialog(
            summaries = summaries.toList(),
            activeId = activeProjectId.value,
            onPick = {
                onProjectChange(ProjectId(it))
                showSwitchDialog = false
            },
            onClose = { showSwitchDialog = false },
        )
    }
}

@Composable
private fun SnapshotsDialog(
    snapshots: List<ProjectSnapshot>,
    pendingLabel: String,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onRestore: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Snapshots") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pendingLabel,
                        onValueChange = onLabelChange,
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.width(280.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                if (snapshots.isEmpty()) {
                    Text("No snapshots yet.")
                } else {
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        items(snapshots, key = { it.id.value }) { snap ->
                            SnapshotRow(snap = snap, onRestore = { onRestore(snap.id.value) })
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SnapshotRow(snap: ProjectSnapshot, onRestore: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(text = snap.label, fontFamily = FontFamily.Monospace)
            Text(
                text = "${snap.id.value.take(10)}  ·  captured ${snap.capturedAtEpochMs}",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF888888),
            )
        }
        Spacer(Modifier.width(4.dp))
        Button(onClick = onRestore) { Text("Restore") }
    }
}

@Composable
private fun SwitchProjectDialog(
    summaries: List<ProjectSummary>,
    activeId: String,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Switch project") },
        text = {
            if (summaries.isEmpty()) {
                Text("No projects.")
            } else {
                LazyColumn(modifier = Modifier.width(420.dp).height(320.dp)) {
                    items(summaries, key = { it.id }) { s ->
                        val active = s.id == activeId
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = (if (active) "• " else "  ") + s.title,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = "${s.id.take(10)}  ·  updated ${s.updatedAtEpochMs}",
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF888888),
                                )
                            }
                            TextButton(
                                enabled = !active,
                                onClick = { onPick(s.id) },
                            ) { Text(if (active) "Active" else "Switch") }
                        }
                    }
                }
            }
        },
    )
}
