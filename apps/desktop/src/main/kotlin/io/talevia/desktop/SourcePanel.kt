package io.talevia.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.ClipsForSourceReport
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.tool.builtin.source.slugifyId
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.uuid.ExperimentalUuidApi

/**
 * Side-panel surface for the VISION §5.1 / §4 expert path — exposes the
 * Source DAG (character refs / style bibles / brand palettes / everything
 * else) for inspection and surgical edits without going through chat.
 *
 * Refresh strategy: subscribes to [BusEvent.PartUpdated] and re-reads the
 * whole project on any part update. A tool call that mutates source ends
 * with a `Part.ToolResult` on the bus, so the panel picks up edits the
 * agent made in the chat pane automatically. Crude but correct — the
 * full project is tiny (JSON blob) and `ProjectStore.get` is an indexed
 * primary-key read.
 *
 * Tool dispatch goes through the same `ToolRegistry` the agent uses; the
 * panel does not reimplement validation or mutation logic.
 *
 * Composition split (part of `debt-split-desktop-source-panel`, 2026-04-23):
 *  - [SourceNodeRow] — per-node inspector with inline edit / generate /
 *    remove actions.
 *  - [SourceGroupHeader] + [groupSourceNodes] in `SourceNodeList.kt` —
 *    group-by-kind bucket layout.
 *  - [AddSourceControls] — "+ character / + style / + palette" form.
 *  - [SourceNodeHelpers.kt] — `dispatchBodyUpdate`, `displayName`,
 *    `nodeSecondaryField`, `nodeSecondaryLabel`, `nodeDescription`,
 *    `PrettyJson`.
 *  - [UiToolContext.kt] — `AppContainer.uiToolContext(projectId)` now
 *    shared across panels that dispatch tools without the agent loop.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun SourcePanel(
    container: AppContainer,
    projectId: ProjectId,
    log: SnapshotStateList<String>,
) {
    val scope = rememberCoroutineScope()
    var project by remember(projectId) { mutableStateOf<Project?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(projectId) {
        project = container.projects.get(projectId)
    }
    LaunchedEffect(projectId) {
        container.bus.subscribe<BusEvent.PartUpdated>().collect {
            project = container.projects.get(projectId)
        }
    }

    fun refresh() {
        scope.launch { project = container.projects.get(projectId) }
    }

    fun dispatch(toolId: String, input: JsonObject, label: String) {
        val tool = container.tools[toolId]
        if (tool == null) {
            log += "$toolId not registered"
            return
        }
        scope.launch {
            runCatching {
                tool.dispatch(input, container.uiToolContext(projectId))
                refresh()
                log += "$label ✓"
            }.onFailure { log += "$label failed: ${friendly(it)}" }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Source DAG",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { refresh() }) { Text("Refresh") }
        }

        val source = project?.source ?: Source.EMPTY
        val groups = groupSourceNodes(source.nodes)

        if (source.nodes.isEmpty()) {
            Text(
                "No source nodes yet. Define a character ref / style bible / brand palette below, " +
                    "or ask the agent in the Chat tab.",
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            Text(
                "revision ${source.revision} · ${source.nodes.size} node${if (source.nodes.size == 1) "" else "s"}",
                modifier = Modifier.padding(vertical = 2.dp),
                fontFamily = FontFamily.Monospace,
            )
        }

        // Precompute per-node downstream-clip reports + the staleness overlay
        // once per project change. Each expanded row reads its own slice.
        // O(nodes × clips) amortized — fine at project scale.
        val p = project
        val staleClipIds = remember(p?.timeline, p?.source, p?.lockfile) {
            p?.staleClipsFromLockfile()?.map { it.clipId }?.toSet().orEmpty()
        }
        val reportsByNode = remember(p?.timeline, p?.source) {
            val map = mutableMapOf<String, List<ClipsForSourceReport>>()
            if (p != null) {
                for (node in p.source.nodes) {
                    map[node.id.value] = p.clipsBoundTo(SourceNodeId(node.id.value))
                }
            }
            map
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
            groups.forEach { (header, nodes) ->
                item(key = "header:$header") { SourceGroupHeader(header, nodes.size) }
                items(nodes, key = { it.id.value }) { node ->
                    SourceNodeRow(
                        node = node,
                        downstreamClips = reportsByNode[node.id.value].orEmpty(),
                        staleClipIds = staleClipIds,
                        expanded = expanded[node.id.value] == true,
                        onToggle = { expanded[node.id.value] = expanded[node.id.value] != true },
                        onRemove = {
                            dispatch(
                                "remove_source_node",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    put("nodeId", node.id.value)
                                },
                                "remove ${displayName(node)}",
                            )
                        },
                        onSave = when (node.kind) {
                            ConsistencyKinds.CHARACTER_REF -> { name, secondary ->
                                dispatchBodyUpdate(
                                    projectId = projectId,
                                    node = node,
                                    label = "edit character ${displayName(node)}",
                                    dispatch = ::dispatch,
                                ) {
                                    if (name.isNotBlank()) put("name", name)
                                    if (secondary.isNotBlank()) put("visualDescription", secondary)
                                }
                            }
                            ConsistencyKinds.STYLE_BIBLE -> { name, secondary ->
                                dispatchBodyUpdate(
                                    projectId = projectId,
                                    node = node,
                                    label = "edit style ${displayName(node)}",
                                    dispatch = ::dispatch,
                                ) {
                                    if (name.isNotBlank()) put("name", name)
                                    if (secondary.isNotBlank()) put("description", secondary)
                                }
                            }
                            ConsistencyKinds.BRAND_PALETTE -> { name, secondary ->
                                val hexList = secondary.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                dispatchBodyUpdate(
                                    projectId = projectId,
                                    node = node,
                                    label = "edit palette ${displayName(node)}",
                                    dispatch = ::dispatch,
                                ) {
                                    if (name.isNotBlank()) put("name", name)
                                    if (hexList.isNotEmpty()) {
                                        put("hexColors", buildJsonArray { hexList.forEach { add(JsonPrimitive(it)) } })
                                    }
                                }
                            }
                            else -> null
                        },
                        onGenerate = {
                            // character_ref → portrait, style_bible → sample scene.
                            // Each uses the node id as the consistency binding so
                            // the generated asset inherits the source → clip →
                            // regenerate lane for free.
                            val prompt = when (node.kind) {
                                ConsistencyKinds.CHARACTER_REF ->
                                    "portrait of ${displayName(node)} — ${nodeDescription(node)}"
                                ConsistencyKinds.STYLE_BIBLE ->
                                    "a sample scene in the style of ${displayName(node)} — ${nodeDescription(node)}"
                                ConsistencyKinds.BRAND_PALETTE ->
                                    "a brand sample for ${displayName(node)}"
                                else -> "a sample artefact for ${displayName(node)}"
                            }
                            dispatch(
                                "generate_image",
                                buildJsonObject {
                                    put("prompt", prompt)
                                    put("projectId", projectId.value)
                                    putJsonArray("consistencyBindingIds") { add(node.id.value) }
                                },
                                "generate from ${displayName(node)}",
                            )
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        AddSourceControls(
            onAddCharacter = { name, desc ->
                dispatch(
                    "add_source_node",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("nodeId", slugifyId(name, "character"))
                        put("kind", ConsistencyKinds.CHARACTER_REF)
                        put(
                            "body",
                            buildJsonObject {
                                put("name", name)
                                put("visualDescription", desc)
                            },
                        )
                    },
                    "add character_ref $name",
                )
            },
            onAddStyle = { name, desc ->
                dispatch(
                    "add_source_node",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("nodeId", slugifyId(name, "style"))
                        put("kind", ConsistencyKinds.STYLE_BIBLE)
                        put(
                            "body",
                            buildJsonObject {
                                put("name", name)
                                put("description", desc)
                            },
                        )
                    },
                    "add style_bible $name",
                )
            },
            onAddPalette = { name, hexCsv ->
                val hexList = hexCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                dispatch(
                    "add_source_node",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("nodeId", slugifyId(name, "brand"))
                        put("kind", ConsistencyKinds.BRAND_PALETTE)
                        put(
                            "body",
                            buildJsonObject {
                                put("name", name)
                                put("hexColors", buildJsonArray { hexList.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    },
                    "add brand_palette $name",
                )
            },
        )
    }
}
