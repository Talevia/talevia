package io.talevia.core.tool.builtin.source

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef

/**
 * Convert a raw `parentIds: List<String>` (as surfaced on the `define_*` tools'
 * JSON schema) into validated [SourceRef]s. The definer tools feed cross-refs
 * into the Source DAG; dangling refs would silently break stale-propagation so
 * we fail loudly at the tool boundary instead.
 *
 * Rules:
 *  - Blank / empty ids are skipped (LLMs occasionally emit `""`).
 *  - `self` cannot reference itself — fail loudly. Lets the Source-layer DAG
 *    invariants stay cycle-free at the entry point.
 *  - Every id must resolve to an existing node in [source] — otherwise the
 *    [SourceNode.parents] list would carry a ref to a ghost, which then shows
 *    up as garbage in `source_query(select=nodes)` and corrupts stale-propagation.
 *  - Deduplicates while preserving the caller's order — repeats in the parent
 *    list carry no extra meaning.
 */
internal fun resolveParentRefs(
    parentIds: List<String>,
    source: Source,
    self: SourceNodeId,
): List<SourceRef> {
    if (parentIds.isEmpty()) return emptyList()
    val index = source.byId
    val seen = mutableSetOf<SourceNodeId>()
    val result = mutableListOf<SourceRef>()
    for (raw in parentIds) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val id = SourceNodeId(trimmed)
        require(id != self) {
            "parentIds must not reference the node being defined (${self.value}); " +
                "cycles are rejected at the Source DAG boundary."
        }
        require(id in index) {
            "parent source node ${id.value} not found in project — define the parent first, " +
                "or use import_source_node to bring it in from another project."
        }
        if (seen.add(id)) result += SourceRef(id)
    }
    return result
}
