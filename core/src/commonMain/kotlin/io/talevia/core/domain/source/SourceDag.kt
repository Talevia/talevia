package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId

/**
 * DAG lane for [Source] — answers "given these nodes changed, which downstream nodes
 * are now stale?". The answer is the seed set plus every node transitively reachable
 * via the reverse-parent edges (a node's [SourceNode.parents] point upstream, so a
 * node is a child of every id in its [SourceNode.parents]).
 *
 * VISION §3.2: "任何 source 改动，要能算出下游哪些 artifact stale、哪些仍有效 — 只重编译
 * 必要的部分". This file is the Source-layer half; the Project-layer half (mapping
 * stale source nodes to stale clips / artifacts) lives in `ProjectStaleness.kt`.
 *
 * Complexity: O(V + E) per call. We don't cache the reverse index across calls because
 * Source is immutable — callers that need a long-lived index should hoist
 * [Source.childIndex] themselves.
 */

/**
 * Reverse-parent index: `id -> set of ids that name this id in their parents`.
 *
 * Precomputed on read, not cached on the class, so changing [Source.nodes] without
 * going through the mutation helpers still produces a correct index. Non-existent
 * parent ids in the data are silently dropped from the index (they produce no edges).
 */
val Source.childIndex: Map<SourceNodeId, Set<SourceNodeId>>
    get() {
        if (nodes.isEmpty()) return emptyMap()
        val out = HashMap<SourceNodeId, MutableSet<SourceNodeId>>(nodes.size)
        for (node in nodes) {
            for (parent in node.parents) {
                out.getOrPut(parent.nodeId) { LinkedHashSet() }.add(node.id)
            }
        }
        return out
    }

/**
 * Compute the stale transitive closure: the set of every node reachable downstream
 * from any id in [changed], including [changed] itself (filtered to ids that exist
 * in this [Source]).
 *
 * Cycle-tolerant — the visited set prevents infinite loops if a user ever constructs
 * one (we don't reject cycles at the data layer; that's a separate linter job).
 *
 * Ids in [changed] that are not present in [nodes] are dropped on input. This keeps
 * callers simple (e.g. "these ids may be stale, but some were removed — just propagate
 * the rest") and matches the semantics callers already expect from [Source.byId].
 */
fun Source.stale(changed: Set<SourceNodeId>): Set<SourceNodeId> {
    if (changed.isEmpty() || nodes.isEmpty()) return emptySet()
    val present = byId.keys
    val seeds = changed.filterTo(LinkedHashSet()) { it in present }
    if (seeds.isEmpty()) return emptySet()

    val children = childIndex
    val visited = LinkedHashSet<SourceNodeId>(seeds)
    val queue = ArrayDeque(seeds)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        val downstream = children[id] ?: continue
        for (child in downstream) {
            if (visited.add(child)) queue.addLast(child)
        }
    }
    return visited
}

/** Convenience for single-id callers. */
fun Source.stale(changed: SourceNodeId): Set<SourceNodeId> = stale(setOf(changed))
