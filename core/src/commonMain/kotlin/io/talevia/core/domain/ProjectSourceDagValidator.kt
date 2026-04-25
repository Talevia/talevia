package io.talevia.core.domain

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source

/**
 * Lightweight structural check over `Project.source`: detects dangling
 * parent refs and parent cycles. Intended for the load-path auto-validation
 * in `FileProjectStore.get` — much narrower than
 * `project_query(select=validation).sourceDagIssues` (which also walks clips, assets,
 * audio envelopes, etc.) so the cost is `O(nodes + edges)` per load.
 *
 * Returns a list of human-readable issue strings. Empty list → clean DAG.
 *
 * Kept as a plain `object` (not inside a class) so the production store,
 * `project_query(select=validation)`, and tests can all share the same algorithm
 * without duplicating DFS logic. The tool's equivalent branch delegates
 * to this helper; drift between the two would mean "project_query(select=validation)
 * passed but load-time warning fired" (or vice versa), which confuses
 * users reading logs.
 */
internal object ProjectSourceDagValidator {

    /**
     * Compute source-DAG issues for [source]. Each returned entry is a
     * fully-rendered warning message. Order: all dangling-parent warnings
     * first (stable by node iteration order), then cycle warnings (one
     * per unique cycle, first time each cycle is discovered).
     */
    fun validate(source: Source): List<String> {
        val nodes = source.nodes
        if (nodes.isEmpty()) return emptyList()
        val byId = source.byId
        val issues = mutableListOf<String>()

        // Tier 1 — dangling parents, reported per-edge.
        for (node in nodes) {
            for (parent in node.parents) {
                if (parent.nodeId !in byId) {
                    issues += "source node '${node.id.value}' references missing parent " +
                        "'${parent.nodeId.value}' (call set_source_node_parents / " +
                        "source_node_action(action=remove) to fix)."
                }
            }
        }

        // Tier 2 — cycle detection via iterative DFS over `parents`.
        // White = unvisited, Grey = on current DFS stack, Black = finished.
        // A back-edge to Grey is a cycle.
        val white = nodes.map { it.id }.toMutableSet()
        val grey = mutableSetOf<SourceNodeId>()
        val black = mutableSetOf<SourceNodeId>()
        val reported = mutableSetOf<Set<SourceNodeId>>()

        fun visit(startId: SourceNodeId) {
            val stack = ArrayDeque<Pair<SourceNodeId, Iterator<SourceNodeId>>>()
            val path = ArrayDeque<SourceNodeId>()

            fun push(id: SourceNodeId) {
                white.remove(id)
                grey += id
                path.addLast(id)
                val parents = byId[id]?.parents.orEmpty().map { it.nodeId }.iterator()
                stack.addLast(id to parents)
            }

            push(startId)
            while (stack.isNotEmpty()) {
                val (currentId, iter) = stack.last()
                if (!iter.hasNext()) {
                    stack.removeLast()
                    grey.remove(currentId)
                    black += currentId
                    path.removeLast()
                    continue
                }
                val next = iter.next()
                if (next !in byId) continue // dangling; already reported above
                if (next in black) continue
                if (next in grey) {
                    val cycleStart = path.indexOf(next)
                    val cycleNodes = if (cycleStart >= 0) {
                        path.toList().subList(cycleStart, path.size).toSet()
                    } else {
                        setOf(next, currentId)
                    }
                    if (reported.add(cycleNodes)) {
                        val rendered = cycleNodes.joinToString(" → ") { it.value } +
                            " → ${next.value}"
                        issues += "source DAG has a parent-cycle involving: $rendered. " +
                            "Use set_source_node_parents to break the cycle."
                    }
                    continue
                }
                push(next)
            }
        }

        while (white.isNotEmpty()) {
            val start = white.first()
            visit(start)
        }

        return issues
    }
}
