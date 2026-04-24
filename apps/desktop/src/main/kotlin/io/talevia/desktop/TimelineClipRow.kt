package io.talevia.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform

/**
 * Per-clip inspector row — collapsed shows headline + chips + action
 * buttons; expanded reveals bindings, filter/volume controls, and the
 * raw JSON body. Split out of `TimelinePanel.kt` as part of
 * `debt-split-desktop-timeline-panel` (2026-04-23).
 */
@Composable
internal fun ClipRow(
    track: Track,
    clip: Clip,
    stale: Boolean,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onToggleSelected: () -> Unit,
    onSeek: () -> Unit,
    onRemove: () -> Unit,
    onRegenerate: () -> Unit,
    onApplyFilter: (name: String, params: Map<String, Float>) -> Unit,
    onSetVolume: (Float) -> Unit,
    onApplyLut: () -> Unit,
    onCaption: () -> Unit,
) {
    val bg = when {
        selected -> Color(0xFFE3EFFF) // blue-ish tint for selection
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
                TextButton(onClick = onToggleSelected) {
                    Text(if (selected) "✓ Sel" else "Sel")
                }
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
                    onCaption = onCaption,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = DesktopPrettyJson.encodeToString(Clip.serializer(), clip),
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

internal fun clipHeadline(clip: Clip): String {
    val start = clip.timeRange.start.inWholeMilliseconds / 1000.0
    val end = clip.timeRange.end.inWholeMilliseconds / 1000.0
    val body = when (clip) {
        is Clip.Video -> "video  ${clip.id.value.take(8)}  ${clip.assetId.value.take(10)}"
        is Clip.Audio -> "audio  ${clip.id.value.take(8)}  ${clip.assetId.value.take(10)}"
        is Clip.Text -> "text   ${clip.id.value.take(8)}  \"${clip.text.take(20)}${if (clip.text.length > 20) "…" else ""}\""
    }
    return "$body  ${formatSeconds(start)}–${formatSeconds(end)}"
}

internal fun clipChips(clip: Clip): List<String> = buildList {
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

private fun isNonDefaultTransform(t: Transform): Boolean =
    t.translateX != 0f ||
        t.translateY != 0f ||
        t.scaleX != 1f ||
        t.scaleY != 1f ||
        t.rotationDeg != 0f ||
        t.opacity != 1f

/**
 * Inline quick-action row shown on expanded clip inspector:
 *  - Video clips: filter preset buttons (apply_filter with pre-set params).
 *  - Audio clips: a volume slider (clip_set_action(field="volume") on release).
 *
 * Intentionally minimal — the full `apply_filter` parameter space is
 * better authored via chat when the user wants to tweak knobs. These
 * buttons cover the "give me the preset I probably want" ergonomic
 * case without forcing experts to drop into a JSON editor.
 */
@Composable
private fun InlineClipActions(
    clip: Clip,
    onApplyFilter: (name: String, params: Map<String, Float>) -> Unit,
    onSetVolume: (Float) -> Unit,
    onApplyLut: () -> Unit,
    onCaption: () -> Unit,
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterPresetButton("bright +") { onApplyFilter("brightness", mapOf("amount" to 0.15f)) }
                FilterPresetButton("bright -") { onApplyFilter("brightness", mapOf("amount" to -0.15f)) }
                FilterPresetButton("sat +") { onApplyFilter("saturation", mapOf("amount" to 0.3f)) }
                FilterPresetButton("sat -") { onApplyFilter("saturation", mapOf("amount" to -0.3f)) }
                FilterPresetButton("blur") { onApplyFilter("blur", mapOf("radius" to 4f)) }
                FilterPresetButton("vignette") { onApplyFilter("vignette", mapOf("intensity" to 0.5f)) }
                FilterPresetButton("LUT…") { onApplyLut() }
                FilterPresetButton("Caption") { onCaption() }
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
                Slider(
                    value = pending,
                    onValueChange = { pending = it },
                    valueRange = 0f..4f,
                    onValueChangeFinished = { onSetVolume(pending) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    FilterPresetButton("Caption") { onCaption() }
                }
            }
        }
        is Clip.Text -> {}
    }
}

@Composable
private fun FilterPresetButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(label) }
}
