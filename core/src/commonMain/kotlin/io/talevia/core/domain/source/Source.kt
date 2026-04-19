package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Structured creative source material for a [io.talevia.core.domain.Project].
 *
 * `Source` is the **genre-agnostic container** — it does not know anything about vlog /
 * narrative / MV / tutorial schemas. Each [SourceNode] carries a [SourceNode.kind]
 * string (e.g. `"vlog.raw_footage"`) and an opaque [kotlinx.serialization.json.JsonElement]
 * body. Genre-specific typed accessors live under `core/domain/source/genre/<genre>/`
 * and never leak back into this file — this is the boundary that keeps Core free of
 * genre-hardcoded schemas (VISION §2, CLAUDE.md anti-requirement).
 *
 * Nodes are stored as a [List] so serialization order is stable (kotlinx-serialization
 * preserves list order round-trip). The [byId] map is a convenience index exposed as a
 * transient derived view; it is rebuilt on deserialization.
 *
 * [revision] is a per-project monotonic counter bumped on every structural mutation
 * via [addNode] / [replaceNode] / [removeNode]. The future DAG / stale-propagation
 * lane will use this (together with node-level [SourceNode.revision] and
 * [SourceNode.contentHash]) to decide which downstream artifacts are stale.
 */
@Serializable
data class Source(
    val revision: Long = 0,
    val nodes: List<SourceNode> = emptyList(),
) {
    @Transient
    val byId: Map<SourceNodeId, SourceNode> = nodes.associateBy { it.id }

    companion object {
        val EMPTY: Source = Source()
    }
}
