package io.talevia.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.talevia.core.ClipId
import io.talevia.core.domain.ClipsForSourceReport
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import kotlinx.serialization.json.JsonObject

/**
 * Inspector-style row for a single source node — collapsed header with
 * name / hash / clip-count badge; expanded view exposes inline edit
 * form + downstream clip list + pretty-printed JSON body.
 *
 * Split out of `SourcePanel.kt` as part of `debt-split-desktop-source-panel`
 * (2026-04-23).
 */
@Composable
internal fun SourceNodeRow(
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
                            text = DesktopPrettyJson.encodeToString(JsonObject.serializer(), node.body as JsonObject),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
