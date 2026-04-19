package io.talevia.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.ClipsForSourceReport
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
                                dispatch(
                                    "update_character_ref",
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("nodeId", node.id.value)
                                        if (name.isNotBlank()) put("name", name)
                                        if (secondary.isNotBlank()) put("visualDescription", secondary)
                                    },
                                    "edit character ${displayName(node)}",
                                )
                            }
                            ConsistencyKinds.STYLE_BIBLE -> { name, secondary ->
                                dispatch(
                                    "update_style_bible",
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("nodeId", node.id.value)
                                        if (name.isNotBlank()) put("name", name)
                                        if (secondary.isNotBlank()) put("description", secondary)
                                    },
                                    "edit style ${displayName(node)}",
                                )
                            }
                            ConsistencyKinds.BRAND_PALETTE -> { name, secondary ->
                                val hexList = secondary.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                dispatch(
                                    "update_brand_palette",
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("nodeId", node.id.value)
                                        if (name.isNotBlank()) put("name", name)
                                        if (hexList.isNotEmpty()) {
                                            put("hexColors", buildJsonArray { hexList.forEach { add(JsonPrimitive(it)) } })
                                        }
                                    },
                                    "edit palette ${displayName(node)}",
                                )
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
                    "define_character_ref",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("name", name)
                        put("visualDescription", desc)
                    },
                    "define character_ref $name",
                )
            },
            onAddStyle = { name, desc ->
                dispatch(
                    "define_style_bible",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("name", name)
                        put("description", desc)
                    },
                    "define style_bible $name",
                )
            },
            onAddPalette = { name, hexCsv ->
                val hexList = hexCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                dispatch(
                    "define_brand_palette",
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("name", name)
                        put("hexColors", buildJsonArray { hexList.forEach { add(JsonPrimitive(it)) } })
                    },
                    "define brand_palette $name",
                )
            },
        )
    }
}

private enum class SourceGroup(val label: String) {
    Characters("Characters"),
    Styles("Style bibles"),
    Palettes("Brand palettes"),
    Other("Other"),
}

private fun groupSourceNodes(nodes: List<SourceNode>): List<Pair<String, List<SourceNode>>> {
    val buckets = linkedMapOf<SourceGroup, MutableList<SourceNode>>().apply {
        put(SourceGroup.Characters, mutableListOf())
        put(SourceGroup.Styles, mutableListOf())
        put(SourceGroup.Palettes, mutableListOf())
        put(SourceGroup.Other, mutableListOf())
    }
    nodes.forEach { node ->
        val bucket = when (node.kind) {
            ConsistencyKinds.CHARACTER_REF -> SourceGroup.Characters
            ConsistencyKinds.STYLE_BIBLE -> SourceGroup.Styles
            ConsistencyKinds.BRAND_PALETTE -> SourceGroup.Palettes
            else -> SourceGroup.Other
        }
        buckets.getValue(bucket).add(node)
    }
    return buckets.entries.filter { it.value.isNotEmpty() }.map { it.key.label to it.value.toList() }
}

@Composable
private fun SourceGroupHeader(label: String, count: Int) {
    Text(
        text = "$label · $count",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceNodeRow(
    node: SourceNode,
    downstreamClips: List<ClipsForSourceReport>,
    staleClipIds: Set<ClipId>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onGenerate: () -> Unit,
    /** null = kind doesn't support inline editing; non-null = save (name, secondary) */
    onSave: ((name: String, secondary: String) -> Unit)? = null,
) {
    val name = displayName(node)
    val staleCount = downstreamClips.count { it.clipId in staleClipIds }
    var editing by remember(node.id.value) { mutableStateOf(false) }
    var editName by remember(node.id.value) { mutableStateOf("") }
    var editSecondary by remember(node.id.value) { mutableStateOf("") }
    val editEnabled by remember(editName, editSecondary) {
        derivedStateOf { editName.isNotBlank() }
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (editing) Color(0xFFECF3FF) else if (expanded) Color(0xFFF1F4FB) else Color(0xFFFAFAFA),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(
                modifier = Modifier.clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = (if (expanded) "▾ " else "▸ ") + name,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                if (downstreamClips.isNotEmpty()) {
                    val suffix = if (staleCount > 0) "  · $staleCount stale" else ""
                    Text(
                        text = "${downstreamClips.size} clip${if (downstreamClips.size == 1) "" else "s"}$suffix",
                        fontFamily = FontFamily.Monospace,
                        color = if (staleCount > 0) Color(0xFF8B5A00) else Color(0xFF757575),
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Text(
                    text = node.contentHash.take(8),
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF757575),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                if (node.kind == ConsistencyKinds.CHARACTER_REF ||
                    node.kind == ConsistencyKinds.STYLE_BIBLE ||
                    node.kind == ConsistencyKinds.BRAND_PALETTE
                ) {
                    TextButton(onClick = onGenerate) { Text("Generate") }
                }
                if (onSave != null && !editing) {
                    TextButton(onClick = {
                        editName = displayName(node)
                        editSecondary = nodeSecondaryField(node)
                        editing = true
                        if (!expanded) onToggle()
                    }) { Text("Edit") }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                if (editing && onSave != null) {
                    // Inline edit form — VISION §5.4 expert path
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editSecondary,
                        onValueChange = { editSecondary = it },
                        label = { Text(nodeSecondaryLabel(node.kind)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = editEnabled,
                            onClick = {
                                onSave(editName.trim(), editSecondary.trim())
                                editing = false
                            },
                        ) { Text("Save") }
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                } else {
                    Text(text = "kind: ${node.kind}", fontFamily = FontFamily.Monospace)
                    Text(text = "id: ${node.id.value}", fontFamily = FontFamily.Monospace)
                    if (node.parents.isNotEmpty()) {
                        Text(
                            text = "parents: ${node.parents.joinToString(", ") { it.nodeId.value }}",
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (downstreamClips.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "downstream clips (${downstreamClips.size}):",
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF555555),
                        )
                        for (r in downstreamClips) {
                            val stale = r.clipId in staleClipIds
                            val viaNote = if (r.directlyBound) "" else "  via ${r.boundVia.joinToString(",") { it.value }}"
                            Text(
                                text = "  ${r.clipId.value.take(8)} on ${r.trackId.value.take(6)}" +
                                    (if (stale) "  [stale]" else "") + viaNote,
                                fontFamily = FontFamily.Monospace,
                                color = if (stale) Color(0xFF8B5A00) else Color(0xFF555555),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = PrettyJson.encodeToString(JsonObject.serializer(), node.body as JsonObject),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddSourceControls(
    onAddCharacter: (name: String, description: String) -> Unit,
    onAddStyle: (name: String, description: String) -> Unit,
    onAddPalette: (name: String, hexCsv: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Define new",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description / hex colors (CSV for palette)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddCharacter(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ character") }
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddStyle(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ style") }
            Button(
                enabled = name.isNotBlank() && description.isNotBlank(),
                onClick = {
                    onAddPalette(name.trim(), description.trim())
                    name = ""; description = ""
                },
            ) { Text("+ palette") }
        }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun displayName(node: SourceNode): String {
    val obj = node.body as? JsonObject
    val name = (obj?.get("name") as? JsonPrimitive)?.content
    return name ?: node.id.value
}

/**
 * Primary editable secondary field value for the inline edit form.
 * Returns a comma-joined string for array fields (brand_palette hexColors).
 */
private fun nodeSecondaryField(node: SourceNode): String {
    val obj = node.body as? JsonObject ?: return ""
    return when (node.kind) {
        ConsistencyKinds.CHARACTER_REF -> (obj["visualDescription"] as? JsonPrimitive)?.content.orEmpty()
        ConsistencyKinds.STYLE_BIBLE -> (obj["description"] as? JsonPrimitive)?.content.orEmpty()
        ConsistencyKinds.BRAND_PALETTE ->
            (obj["hexColors"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.content ?: "" }.orEmpty()
        else -> ""
    }
}

/** Label for the secondary TextField in the inline edit form. */
private fun nodeSecondaryLabel(kind: String): String = when (kind) {
    ConsistencyKinds.CHARACTER_REF -> "Visual description"
    ConsistencyKinds.STYLE_BIBLE -> "Description"
    ConsistencyKinds.BRAND_PALETTE -> "Hex colors (comma-separated)"
    else -> "Value"
}

/**
 * Short descriptive blurb pulled from a node body — used as the tail of
 * "Generate" button prompts. Different kinds keep the relevant field
 * under different keys (character_ref: visualDescription; style_bible:
 * description; brand_palette: name only). Fall back to "" when the body
 * doesn't fit any expected shape.
 */
private fun nodeDescription(node: SourceNode): String {
    val obj = node.body as? JsonObject ?: return ""
    val candidates = listOf("visualDescription", "description")
    for (key in candidates) {
        val v = (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (v != null) return v
    }
    return ""
}

/**
 * Minimal [ToolContext] for panels that dispatch tools directly (no agent
 * loop). Same pattern as the existing centre-panel buttons — permissions
 * still go through the container's real permission service.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun AppContainer.uiToolContext(projectId: ProjectId): ToolContext {
    val sid = SessionId(projectId.value)
    val mid = io.talevia.core.MessageId(Uuid.random().toString())
    val cid = io.talevia.core.CallId(Uuid.random().toString())
    return ToolContext(
        sessionId = sid,
        messageId = mid,
        callId = cid,
        askPermission = { permissions.check(permissionRules.toList(), it) },
        emitPart = { p -> sessions.upsertPart(p) },
        messages = emptyList(),
    )
}
