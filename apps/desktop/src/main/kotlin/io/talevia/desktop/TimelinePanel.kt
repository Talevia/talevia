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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.domain.staleClipsFromLockfile
import kotlinx.coroutines.launch
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
            }.onFailure { log += "$label failed: ${friendly(it)}" }
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

    // Multi-select state: Set<clipId>. cmd+click on a ClipRow toggles
    // membership; the header row shows batch actions when any are selected.
    val selected = remember(projectId) { mutableStateListOf<String>() }

    fun undoLastMutation() {
        scope.launch {
            // UI-emitted snapshots all land under SessionId(projectId.value) —
            // see uiToolContext. Pull the session's parts, filter to
            // TimelineSnapshots in order, and revert to the *second-to-last*
            // (the state right before the most recent mutation landed).
            val uiSessionId = io.talevia.core.SessionId(projectId.value)
            val snapshots = runCatching {
                container.sessions.listSessionParts(uiSessionId, includeCompacted = false)
                    .filterIsInstance<io.talevia.core.session.Part.TimelineSnapshot>()
            }.getOrElse { emptyList() }
            if (snapshots.size < 2) {
                log += "nothing to undo"
                return@launch
            }
            val target = snapshots[snapshots.size - 2]
            runCatching {
                container.tools["revert_timeline"]!!.dispatch(
                    buildJsonObject {
                        put("projectId", projectId.value)
                        put("snapshotPartId", target.id.value)
                    },
                    container.uiToolContext(projectId),
                )
                project = container.projects.get(projectId)
                log += "undo → snapshot ${target.id.value.take(8)}"
            }.onFailure { log += "undo failed: ${friendly(it)}" }
        }
    }

    fun batchApply(name: String, params: Map<String, Float>) {
        if (selected.isEmpty()) return
        val snapshot = selected.toList()
        dispatch(
            "apply_filter",
            buildJsonObject {
                put("projectId", projectId.value)
                put("filterName", name)
                putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
                putJsonArray("clipIds") { snapshot.forEach { add(it) } }
            },
            "apply $name × ${snapshot.size}",
        )
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
            if (selected.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${selected.size} selected",
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD97706),
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { batchApply("brightness", mapOf("amount" to 0.15f)) }) { Text("bright+") }
                TextButton(onClick = { batchApply("saturation", mapOf("amount" to 0.3f)) }) { Text("sat+") }
                TextButton(onClick = { batchApply("blur", mapOf("radius" to 4f)) }) { Text("blur") }
                TextButton(onClick = { batchApply("vignette", mapOf("intensity" to 0.5f)) }) { Text("vignette") }
                TextButton(onClick = { selected.clear() }) { Text("clear") }
            } else {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { undoLastMutation() }) { Text("Undo") }
            }
        }

        // Stale-summary banner: visible as soon as any source node edit bumps
        // a contentHash that a lockfile entry was keyed on. Gives the expert a
        // single-glance view of cascade stale count after inline source edits
        // (VISION §3.2 "refactor propagation").
        if (staleIds.isNotEmpty()) {
            Surface(
                color = Color(0xFFFFF4E5),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${staleIds.size} clip${if (staleIds.size == 1) "" else "s"} stale — source changed",
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF8B5A00),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        dispatch(
                            "regenerate_stale_clips",
                            buildJsonObject { put("projectId", projectId.value) },
                            "regenerate all ${staleIds.size} stale",
                        )
                    }) { Text("Regenerate All") }
                }
            }
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
                    val isSelected = clip.id.value in selected
                    ClipRow(
                        track = track,
                        clip = clip,
                        stale = clip.id in staleIds,
                        expanded = expanded[clip.id.value] == true,
                        selected = isSelected,
                        onToggle = {
                            expanded[clip.id.value] = expanded[clip.id.value] != true
                        },
                        onToggleSelected = {
                            if (isSelected) selected.remove(clip.id.value) else selected.add(clip.id.value)
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
                        onCaption = {
                            dispatch(
                                "auto_subtitle_clip",
                                buildJsonObject {
                                    put("projectId", projectId.value)
                                    put("clipId", clip.id.value)
                                },
                                "caption ${clip.id.value.take(6)}",
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
                                @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
                                val assetId = runCatching {
                                    val metadata = container.engine.probe(picked)
                                    val newId = io.talevia.core.AssetId(kotlin.uuid.Uuid.random().toString())
                                    val asset = io.talevia.core.domain.MediaAsset(
                                        id = newId,
                                        source = picked,
                                        metadata = metadata,
                                    )
                                    container.projects.mutate(projectId) { p ->
                                        p.copy(assets = p.assets + asset)
                                    }
                                    newId
                                }.getOrElse {
                                    log += "LUT import failed: ${friendly(it)}"
                                    return@launch
                                }
                                dispatch(
                                    "apply_lut",
                                    buildJsonObject {
                                        put("projectId", projectId.value)
                                        put("clipId", clip.id.value)
                                        put("lutAssetId", assetId.value)
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

internal fun formatSeconds(s: Double): String =
    if (s <= 0.0) "0.0s" else "%.1fs".format(s)
