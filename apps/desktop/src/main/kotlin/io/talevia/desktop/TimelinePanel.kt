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
import io.talevia.core.domain.staleClipsFromLockfile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    onSeekPreview: (Double) -> Unit = {},
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
    // Stale badge sourced from the lockfile — VISION §3.2 semantics.
    // A clip is stale iff its backing asset has a lockfile entry whose
    // `sourceContentHashes` no longer matches the project's current source
    // hashes. Imported / non-AIGC clips and clips whose lockfile entries
    // predate `sourceContentHashes` won't flash stale — that's the right
    // behaviour: we only know what we've actually snapshotted.
    val staleIds = remember(p?.timeline, p?.source, p?.lockfile) {
        p?.staleClipsFromLockfile()?.map { it.clipId }?.toSet().orEmpty()
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
                        onSeek = {
                            onSeekPreview(clip.timeRange.start.inWholeMilliseconds / 1000.0)
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
                        onRegenerate = {
                            dispatch(
                                "regenerate_stale_clips",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    putJsonArray("clipIds") { add(clip.id.value) }
                                },
                                "regenerate clip ${clip.id.value.take(6)}",
                            )
                        },
                        onApplyFilter = { name, params ->
                            dispatch(
                                "apply_filter",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    put("clipId", clip.id.value)
                                    put("filterName", name)
                                    putJsonObject("params") {
                                        params.forEach { (k, v) -> put(k, v) }
                                    }
                                },
                                "apply $name to ${clip.id.value.take(6)}",
                            )
                        },
                        onSetVolume = { v ->
                            dispatch(
                                "set_clip_volume",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    put("clipId", clip.id.value)
                                    put("volume", v)
                                },
                                "volume ${"%.2f".format(v)} on ${clip.id.value.take(6)}",
                            )
                        },
                        onApplyLut = {
                            // Picker → import → apply_lut, all inside one scope.launch so
                            // the dialog blocking doesn't freeze recomposition.
                            scope.launch {
                                val picked = runCatching {
                                    container.filePicker.pick(
                                        filter = io.talevia.core.platform.FileFilter.Any,
                                        title = "Choose a .cube LUT",
                                    )
                                }.getOrNull() ?: run {
                                    log += "LUT picker cancelled"
                                    return@launch
                                }
                                if (picked !is io.talevia.core.domain.MediaSource.File) {
                                    log += "LUT picker: unsupported source"
                                    return@launch
                                }
                                val asset = runCatching {
                                    container.media.import(picked) { container.engine.probe(it) }
                                }.getOrElse {
                                    log += "LUT import failed: ${it.message}"
                                    return@launch
                                }
                                dispatch(
                                    "apply_lut",
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("clipId", clip.id.value)
                                        put("lutAssetId", asset.id.value)
                                    },
                                    "apply LUT ${picked.path.substringAfterLast('/')} to ${clip.id.value.take(6)}",
                                )
                            }
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
    onSeek: () -> Unit,
    onRemove: () -> Unit,
    onRegenerate: () -> Unit,
    onApplyFilter: (name: String, params: Map<String, Float>) -> Unit,
    onSetVolume: (Float) -> Unit,
    onApplyLut: () -> Unit,
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
                if (stale) {
                    Chip("stale", color = Color(0xFFD97706))
                    TextButton(onClick = onRegenerate) { Text("Regenerate") }
                }
                TextButton(onClick = onSeek) { Text("Seek") }
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
                InlineClipActions(
                    clip = clip,
                    onApplyFilter = onApplyFilter,
                    onSetVolume = onSetVolume,
                    onApplyLut = onApplyLut,
                )
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

/**
 * Inline quick-action row shown on expanded clip inspector:
 *  - Video clips: filter preset buttons (apply_filter with pre-set params).
 *  - Audio clips: a volume slider (set_clip_volume on release).
 *
 * Intentionally minimal — the full `apply_filter` parameter space is better
 * authored via chat when the user wants to tweak knobs. These buttons cover
 * the "give me the preset I probably want" ergonomic case without forcing
 * experts to drop into a JSON editor.
 */
@Composable
private fun InlineClipActions(
    clip: Clip,
    onApplyFilter: (name: String, params: Map<String, Float>) -> Unit,
    onSetVolume: (Float) -> Unit,
    onApplyLut: () -> Unit,
) {
    when (clip) {
        is Clip.Video -> {
            Text(
                "filters:",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF555555),
            )
            Row(
                modifier = Modifier.padding(vertical = 2.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                FilterPresetButton("bright +") { onApplyFilter("brightness", mapOf("amount" to 0.15f)) }
                FilterPresetButton("bright -") { onApplyFilter("brightness", mapOf("amount" to -0.15f)) }
                FilterPresetButton("sat +") { onApplyFilter("saturation", mapOf("amount" to 0.3f)) }
                FilterPresetButton("sat -") { onApplyFilter("saturation", mapOf("amount" to -0.3f)) }
                FilterPresetButton("blur") { onApplyFilter("blur", mapOf("radius" to 4f)) }
                FilterPresetButton("vignette") { onApplyFilter("vignette", mapOf("intensity" to 0.5f)) }
                FilterPresetButton("LUT…") { onApplyLut() }
            }
        }
        is Clip.Audio -> {
            // Local slider state so dragging is smooth; only commit the tool
            // call on release (onValueChangeFinished) to avoid N permission
            // prompts per drag.
            var pending by remember(clip.id.value) { mutableStateOf(clip.volume) }
            Column {
                Text(
                    "volume: ${"%.2f".format(pending)}",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF555555),
                )
                androidx.compose.material3.Slider(
                    value = pending,
                    onValueChange = { pending = it },
                    valueRange = 0f..4f,
                    onValueChangeFinished = { onSetVolume(pending) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is Clip.Text -> {}
    }
}

@Composable
private fun FilterPresetButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(label) }
}
