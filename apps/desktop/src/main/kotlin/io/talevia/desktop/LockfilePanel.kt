package io.talevia.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Project
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.staleClipsFromLockfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lockfile + stale-clip visibility (VISION §3.1 / §3.2) on the desktop.
 *
 * Surfaces two coupled views:
 *  - **Lockfile entries**: every AIGC generation the project has run,
 *    with tool, model, seed, inputHash prefix, and (when the entry is
 *    bound to source nodes) a fresh / stale tag derived from
 *    `Project.staleClipsFromLockfile()`.
 *  - **Stale clips**: the stale-clip report pre-computed by the same
 *    function, with a single `Regenerate N` button at the section
 *    header that dispatches `regenerate_stale_clips` through the shared
 *    registry — one click closes the VISION §6 refactor loop
 *    (edit character → UI shows stale → click → timeline refreshes).
 *
 * Mutations go through the existing tool (`regenerate_stale_clips`);
 * this panel is the UI half of what `list_lockfile_entries` /
 * `find_stale_clips` already expose to the agent.
 */
@Composable
fun LockfilePanel(
    container: AppContainer,
    projectId: ProjectId,
    log: SnapshotStateList<String>,
) {
    val scope = rememberCoroutineScope()
    var project by remember(projectId) { mutableStateOf<Project?>(null) }
    var staleOnly by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(projectId) { project = container.projects.get(projectId) }
    LaunchedEffect(projectId) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect {
            project = container.projects.get(projectId)
        }
    }

    val p = project
    val staleReports = remember(p) { p?.staleClipsFromLockfile().orEmpty() }
    val staleAssetIds = remember(staleReports) { staleReports.map { it.assetId }.toSet() }

    fun regenerateStale() {
        val tool = container.tools["regenerate_stale_clips"]
        if (tool == null) {
            log += "regenerate_stale_clips not registered"; return
        }
        if (regenerating) return
        regenerating = true
        val input = buildJsonObject { put("projectId", projectId.value) }
        scope.launch {
            runCatching {
                val result = tool.dispatch(input, container.uiToolContext(projectId))
                log += "regenerate_stale_clips → ${result.outputForLlm}"
                project = container.projects.get(projectId)
            }.onFailure { log += "regenerate_stale_clips failed: ${friendly(it)}" }
            regenerating = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Lockfile", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            val count = p?.lockfile?.entries?.size ?: 0
            Text(
                "$count entr${if (count == 1) "y" else "ies"} · ${staleReports.size} stale",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF888888),
            )
            Spacer(Modifier.weight(1f))
            Text("Stale only", modifier = Modifier.padding(end = 4.dp))
            Switch(checked = staleOnly, onCheckedChange = { staleOnly = it })
        }

        if (staleReports.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Stale clips",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = !regenerating,
                    onClick = { regenerateStale() },
                ) {
                    Text(if (regenerating) "regenerating…" else "regenerate ${staleReports.size}")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                items(staleReports, key = { it.clipId.value }) { report ->
                    StaleClipRow(
                        clipId = report.clipId.value,
                        assetId = report.assetId.value,
                        changedIds = report.changedSourceIds.map { it.value },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Entries",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        val entries = p?.lockfile?.entries.orEmpty()
        val visible = if (staleOnly) {
            entries.filter { it.assetId in staleAssetIds }
        } else {
            entries
        }

        if (visible.isEmpty()) {
            Text(
                if (entries.isEmpty()) {
                    "No AIGC generations yet. Every generate_image / generate_video / " +
                        "synthesize_speech call will pin its inputs + model + seed here."
                } else {
                    "No stale entries."
                },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                items(visible, key = { it.inputHash }) { entry ->
                    LockfileEntryRow(
                        entry = entry,
                        stale = entry.assetId in staleAssetIds,
                        expanded = expanded[entry.inputHash] == true,
                        onToggle = { expanded[entry.inputHash] = expanded[entry.inputHash] != true },
                    )
                }
            }
        }
    }
}

@Composable
private fun StaleClipRow(
    clipId: String,
    assetId: String,
    changedIds: List<String>,
) {
    Surface(
        color = Color(0xFFFFF4E5),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = "clip ${clipId.take(8)}  asset ${assetId.take(10)}",
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "changed: ${changedIds.joinToString(", ")}",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8B5A00),
                )
            }
        }
    }
}

@Composable
private fun LockfileEntryRow(
    entry: LockfileEntry,
    stale: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val bg = when {
        expanded -> Color(0xFFF1F4FB)
        stale -> Color(0xFFFFF4E5)
        else -> Color(0xFFFAFAFA)
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(
                modifier = Modifier.clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (expanded) "▾ " else "▸ ") +
                        "${entry.toolId}  ${entry.inputHash.take(10)}",
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = entry.provenance.modelId +
                        (entry.provenance.modelVersion?.let { "@$it" } ?: "") +
                        "  seed ${entry.provenance.seed}",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF555555),
                )
                if (stale) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {}, enabled = false) { Text("stale") }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "assetId:   ${entry.assetId.value}",
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "provider:  ${entry.provenance.providerId}",
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "created:   ${entry.provenance.createdAtEpochMs}",
                    fontFamily = FontFamily.Monospace,
                )
                if (entry.sourceBinding.isNotEmpty()) {
                    Text(
                        text = "bindings:  ${entry.sourceBinding.joinToString(", ") { it.value }}",
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (entry.sourceContentHashes.isNotEmpty()) {
                    Text(
                        text = "snapshotHashes:",
                        fontFamily = FontFamily.Monospace,
                    )
                    entry.sourceContentHashes.forEach { (id, hash) ->
                        Text(
                            text = "  ${id.value}: ${hash.take(12)}",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = PrettyJson.encodeToString(JsonObject.serializer(), entry.provenance.parameters),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val PrettyJson = Json(JsonConfig.default) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
