package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId

/**
 * Iterative DFS over the parent-edge DAG. Returns the first cycle
 * found as a list of [SourceNodeId]s closing on themselves
 * (`[A, B, C, A]` means `A → B → C → A`), or `null` if the graph is
 * acyclic.
 *
 * Why this exists: `Source.replaceNode` lets a caller change a node's
 * `parents` list. A user setting node-A's parents to include a node
 * that is already a *descendant* of A turns the DAG into a cyclic
 * graph. Downstream consumers ([deepContentHashOf], in particular)
 * recurse over parent edges; without a cycle guard, the recursion
 * stack-overflows.
 *
 * The walk is purely additive — it does NOT mutate [Source] and is
 * safe to call from read paths (validation, diagnostics). Mutation
 * paths in [SourceMutations] call this post-write and throw
 * [IllegalStateException] when a cycle is found, so cycles never
 * land on disk.
 *
 * Cost: O(V + E) — each node is visited at most twice
 * (`inProgress` push + `done` pop). For typical Source DAGs (≤ 100
 * nodes, ≤ a few parents each) this is sub-microsecond.
 */
fun Source.cycleAt(): List<SourceNodeId>? {
    val byId = this.byId
    // 0 = unvisited; 1 = on the current DFS stack (in-progress);
    // 2 = fully explored. Three colours so back-edges are
    // distinguishable from cross-edges to already-explored siblings.
    val state = HashMap<SourceNodeId, Int>(byId.size * 2)
    val stack = ArrayDeque<DfsFrame>()

    for (root in byId.keys) {
        if (state[root] == 2) continue
        stack.addLast(DfsFrame(root, parentIterator(byId, root)))
        state[root] = 1
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.parentsLeft.hasNext()) {
                state[frame.node] = 2
                stack.removeLast()
                continue
            }
            val parent = frame.parentsLeft.next()
            // Skip dangling refs — they're a separate diagnostic
            // surfaced by project_query(select=validation), not a cycle.
            if (parent !in byId) continue
            when (state[parent]) {
                1 -> {
                    // Back-edge: parent is on the current DFS stack.
                    // Walk the stack to reconstruct the cycle.
                    val cycleStart = stack.indexOfFirst { it.node == parent }
                    val cycle = stack.subList(cycleStart, stack.size).map { it.node } + parent
                    return cycle
                }
                2 -> Unit  // already fully explored, no cycle through here
                else -> {
                    state[parent] = 1
                    stack.addLast(DfsFrame(parent, parentIterator(byId, parent)))
                }
            }
        }
    }
    return null
}

private class DfsFrame(
    val node: SourceNodeId,
    val parentsLeft: Iterator<SourceNodeId>,
)

private fun parentIterator(
    byId: Map<SourceNodeId, SourceNode>,
    nodeId: SourceNodeId,
): Iterator<SourceNodeId> = (byId[nodeId]?.parents.orEmpty())
    .map { it.nodeId }
    .iterator()
