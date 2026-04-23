package io.talevia.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.ConsistencyKinds

/**
 * Group buckets used by the source-node list view. Split out of
 * `SourcePanel.kt` as part of `debt-split-desktop-source-panel`
 * (2026-04-23).
 */
private enum class SourceGroup(val label: String) {
    Characters("Characters"),
    Styles("Style bibles"),
    Palettes("Brand palettes"),
    Other("Other"),
}

/**
 * Bucket [nodes] by consistency kind for the SourcePanel node list.
 * Preserves input order within each bucket (so the LazyColumn reads
 * top-to-bottom the same way the user sees the DAG).
 */
internal fun groupSourceNodes(nodes: List<SourceNode>): List<Pair<String, List<SourceNode>>> {
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
internal fun SourceGroupHeader(label: String, count: Int) {
    Text(
        text = "$label · $count",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}
