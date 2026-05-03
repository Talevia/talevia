package io.talevia.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.query.SnapshotRow
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Browse and restore project snapshots (VISION §3.4 "可版本化 / 可回滚").
 *
 * Lists all snapshots newest-first. Each row shows the label, a
 * human-readable timestamp, and the clip count captured at save time.
 * "Restore" dispatches `project_snapshot_action(action=restore)` through the shared
 * [AppContainer.tools] registry so validation + bus events are identical
 * to agent-driven restores. "Save now" dispatches `project_snapshot_action(action=save)`
 * for quick manual captures without going through chat.
 *
 * Refresh strategy: same BusEvent.PartUpdated subscription as SourcePanel
 * and TimelinePanel — any tool that mutates the project (including a
 * restore itself) triggers a re-read of the snapshots list.
 */
@Composable
fun SnapshotPanel(
    container: AppContainer,
    projectId: ProjectId,
    log: SnapshotStateList<String>,
) {
    val scope = rememberCoroutineScope()
    val snapshots = remember(projectId) {
        mutableStateListOf<SnapshotRow>()
    }
    var restoring by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val tool = container.tools["project_query"] ?: return
        runCatching {
            val result = tool.dispatch(
                buildJsonObject {
                    put("projectId", projectId.value)
                    put("select", "snapshots")
                },
                container.uiToolContext(projectId),
            )
            val data = result.data as? ProjectQueryTool.Output ?: return
            val rows = JsonConfig.default.decodeFromJsonElement(
                ListSerializer(SnapshotRow.serializer()),
                data.rows,
            )
            snapshots.clear()
            snapshots.addAll(rows)
        }.onFailure { log += "snapshots load failed: ${friendly(it)}" }
    }

    LaunchedEffect(projectId) { reload() }
    LaunchedEffect(projectId) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect { reload() }
    }

    fun saveNow() {
        scope.launch {
            runCatching {
                container.tools["project_action"]!!.dispatch(
                    buildJsonObject {
                        put("kind", "snapshot")
                        put(
                            "args",
                            buildJsonObject {
                                put("projectId", projectId.value)
                                put("action", "save")
                            },
                        )
                    },
                    container.uiToolContext(projectId),
                )
                log += "snapshot saved"
            }.onFailure { log += "snapshot save failed: ${friendly(it)}" }
        }
    }

    fun restore(snapshotId: String, label: String) {
        restoring = snapshotId
        scope.launch {
            runCatching {
                container.tools["project_action"]!!.dispatch(
                    buildJsonObject {
                        put("kind", "snapshot")
                        put(
                            "args",
                            buildJsonObject {
                                put("projectId", projectId.value)
                                put("action", "restore")
                                put("snapshotId", snapshotId)
                            },
                        )
                    },
                    container.uiToolContext(projectId),
                )
                log += "restored snapshot \"$label\""
            }.onFailure { log += "restore failed: ${friendly(it)}" }
            restoring = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Snapshots",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Button(onClick = { saveNow() }) { Text("Save now  (Cmd+S)") }
        }

        if (snapshots.isEmpty()) {
            Text(
                "No snapshots yet. Click \"Save now\" or press Cmd+S to capture the current state.",
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            Text(
                "${snapshots.size} snapshot${if (snapshots.size == 1) "" else "s"} · newest first",
                modifier = Modifier.padding(vertical = 2.dp),
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF757575),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
            items(snapshots, key = { it.snapshotId }) { snap ->
                SnapshotRow(
                    snap = snap,
                    restoring = restoring == snap.snapshotId,
                    onRestore = { restore(snap.snapshotId, snap.label) },
                )
            }
        }
    }
}

@Composable
private fun SnapshotRow(
    snap: SnapshotRow,
    restoring: Boolean,
    onRestore: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (restoring) Color(0xFFE8F5E9) else Color(0xFFFAFAFA),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(snap.label, fontFamily = FontFamily.Monospace)
                Text(
                    text = "${formatEpochMs(snap.capturedAtEpochMs)} · ${snap.clipCount} clip${if (snap.clipCount == 1) "" else "s"}",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF757575),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (restoring) {
                Text("Restoring…", fontFamily = FontFamily.Monospace, color = Color(0xFF388E3C))
            } else {
                TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}

/** Minimal epoch-ms → human-readable string without platform date APIs. */
private fun formatEpochMs(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    return instant.toString().take(19).replace('T', ' ')
}
