package io.talevia.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.domain.staleClips
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Rich timeline inspector for the desktop editor — replaces the earlier
 * flat list of clips. Groups clips by track, surfaces filters / volume /
 * transforms / source bindings per clip, flags stale clips (VISION §3.2),
 * and dispatches `remove_clip` through the shared `ToolRegistry`.
 *
 * Same refresh strategy as [SourcePanel]: subscribe to
 * `BusEvent.PartUpdated` and re-read the project — tool mutations picked
 * up from any source (agent, Source panel, centre buttons) without
 * bespoke wiring.
 */
@Composable
fun TimelinePanel(
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

    fun dispatch(toolId: String, input: JsonObject, label: String) {
        val tool = container.tools[toolId]
        if (tool == null) {
            log += "$toolId not registered"; return
        }
        scope.launch {
            runCatching {
                tool.dispatch(input, container.uiToolContext(projectId))
                project = container.projects.get(projectId)
                log += "$label ✓"
            }.onFailure { log += "$label failed: ${it.message}" }
        }
    }

    val p = project
    val tracks = p?.timeline?.tracks ?: emptyList()
    // Stale set: any clip whose sourceBinding intersects the transitive closure of
    // "changed" source nodes. For now we don't track "which nodes changed since
    // the last render" — pass every node id, so the helper reports every clip
    // with a stale-capable dependency. Good enough for an initial visibility
    // badge; upgrade when we wire a real stale-since-render signal.
    val allNodeIds = p?.source?.nodes?.map { it.id }?.toSet().orEmpty()
    val staleIds = remember(p?.timeline, allNodeIds) {
        p?.staleClips(allNodeIds).orEmpty()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Tracks",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "duration " + formatSeconds(p?.timeline?.duration?.inWholeMilliseconds?.div(1000.0) ?: 0.0),
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF555555),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${tracks.sumOf { it.clips.size }} clips",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF555555),
            )
        }

        if (tracks.isEmpty()) {
            Text(
                "No tracks yet. Add a clip from the Assets panel, or ask the agent.",
                modifier = Modifier.padding(vertical = 6.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            tracks.forEach { track ->
                item(key = "track:${track.id.value}") { TrackHeader(track) }
                items(track.clips, key = { it.id.value }) { clip ->
                    ClipRow(
                        track = track,
                        clip = clip,
                        stale = clip.id in staleIds,
                        expanded = expanded[clip.id.value] == true,
                        onToggle = {
                            expanded[clip.id.value] = expanded[clip.id.value] != true
                        },
                        onRemove = {
                            dispatch(
                                "remove_clip",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    put("clipId", clip.id.value)
                                },
                                "remove clip ${clip.id.value.take(6)}",
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(track: Track) {
    val kind = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }
    Text(
        text = "[$kind] ${track.id.value.take(8)} · ${track.clips.size} clip${if (track.clips.size == 1) "" else "s"}",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        color = Color(0xFF4C566A),
    )
}

@Composable
private fun ClipRow(
    track: Track,
    clip: Clip,
    stale: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
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
                    text = (if (expanded) "▾ " else "▸ ") + clipHeadline(clip),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                clipChips(clip).forEach { Chip(it) }
                if (stale) Chip("stale", color = Color(0xFFD97706))
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text("track: ${track.id.value}", fontFamily = FontFamily.Monospace)
                Text("clip:  ${clip.id.value}", fontFamily = FontFamily.Monospace)
                if (clip.sourceBinding.isNotEmpty()) {
                    Text(
                        text = "bindings: ${clip.sourceBinding.joinToString(", ") { it.value }}",
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = PrettyJson.encodeToString(Clip.serializer(), clip),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color = Color(0xFF6B778D)) {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace, color = color)
    }
}

private fun clipHeadline(clip: Clip): String {
    val start = clip.timeRange.start.inWholeMilliseconds / 1000.0
    val end = clip.timeRange.end.inWholeMilliseconds / 1000.0
    val body = when (clip) {
        is Clip.Video -> "video  ${clip.id.value.take(8)}  ${clip.assetId.value.take(10)}"
        is Clip.Audio -> "audio  ${clip.id.value.take(8)}  ${clip.assetId.value.take(10)}"
        is Clip.Text -> "text   ${clip.id.value.take(8)}  \"${clip.text.take(20)}${if (clip.text.length > 20) "…" else ""}\""
    }
    return "$body  ${formatSeconds(start)}–${formatSeconds(end)}"
}

private fun clipChips(clip: Clip): List<String> = buildList {
    when (clip) {
        is Clip.Video -> {
            if (clip.filters.isNotEmpty()) add("${clip.filters.size}fx")
            if (clip.transforms.any(::isNonDefaultTransform)) add("xform")
        }
        is Clip.Audio -> {
            if (clip.volume != 1.0f) add("vol ${"%.2f".format(clip.volume)}")
            if (clip.fadeInSeconds > 0f) add("fi ${"%.1fs".format(clip.fadeInSeconds)}")
            if (clip.fadeOutSeconds > 0f) add("fo ${"%.1fs".format(clip.fadeOutSeconds)}")
        }
        is Clip.Text -> {}
    }
}

private fun isNonDefaultTransform(t: io.talevia.core.domain.Transform): Boolean =
    t.translateX != 0f ||
        t.translateY != 0f ||
        t.scaleX != 1f ||
        t.scaleY != 1f ||
        t.rotationDeg != 0f ||
        t.opacity != 1f

private fun formatSeconds(s: Double): String =
    if (s <= 0.0) "0.0s" else "%.1fs".format(s)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val PrettyJson = Json(JsonConfig.default) {
    prettyPrint = true
    prettyPrintIndent = "  "
}
