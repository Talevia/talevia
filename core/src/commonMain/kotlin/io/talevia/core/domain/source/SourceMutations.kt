package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import io.talevia.core.util.contentHashOf

/**
 * Pure, value-level mutations on [Source]. Each returns a new [Source] with:
 *  - the [Source]'s [Source.revision] bumped by one,
 *  - the touched node's [SourceNode.revision] bumped by one,
 *  - the touched node's [SourceNode.contentHash] recomputed from `(kind, body, parents)`.
 *
 * These are genre-agnostic. Genre layers call them via typed builders in
 * `core/domain/source/genre/<genre>/`.
 */

/**
 * Append [node]. The caller is responsible for choosing a unique [SourceNode.id];
 * we fail loudly on collision rather than silently overwrite — that's what
 * [replaceNode] is for.
 */
fun Source.addNode(node: SourceNode): Source {
    require(nodes.none { it.id == node.id }) {
        "SourceNode ${node.id.value} already exists; use replaceNode(id, ...) to update"
    }
    val prepared = node.bumpedForWrite()
    val next = copy(
        revision = revision + 1,
        nodes = nodes + prepared,
    )
    next.requireAcyclic("addNode(${node.id.value})")
    return next
}

/**
 * Replace the node with [id] by applying [updater] to it. Throws if no such node exists.
 * The updater may change any field **except** [SourceNode.id] — we assert identity
 * preservation to keep references stable.
 */
fun Source.replaceNode(id: SourceNodeId, updater: (SourceNode) -> SourceNode): Source {
    val index = nodes.indexOfFirst { it.id == id }
    require(index >= 0) { "SourceNode ${id.value} not found" }
    val current = nodes[index]
    val next = updater(current)
    require(next.id == id) {
        "replaceNode must not change id: expected ${id.value}, got ${next.id.value}"
    }
    val bumped = next.bumpedForWrite()
    val updatedSource = copy(
        revision = revision + 1,
        nodes = nodes.toMutableList().also { it[index] = bumped },
    )
    updatedSource.requireAcyclic("replaceNode(${id.value})")
    return updatedSource
}

/**
 * Remove the node with [id]. Throws if no such node exists. Does not cascade — cleaning
 * up dangling [SourceRef] entries on other nodes is the DAG lane's job.
 */
fun Source.removeNode(id: SourceNodeId): Source {
    val index = nodes.indexOfFirst { it.id == id }
    require(index >= 0) { "SourceNode ${id.value} not found" }
    return copy(
        revision = revision + 1,
        nodes = nodes.toMutableList().also { it.removeAt(index) },
    )
}

/**
 * Internal: bump a node's [SourceNode.revision] + recompute [SourceNode.contentHash]
 * from the new `(kind, body, parents)`. Kept private to this file so genre code cannot
 * forget to bump on write — all writes go through [addNode] / [replaceNode].
 */
private fun SourceNode.bumpedForWrite(): SourceNode = copy(
    revision = revision + 1,
    contentHash = contentHashOf(kind, body, parents),
)

/**
 * Throws [IllegalStateException] if [Source.cycleAt] finds a cycle.
 * Intended to run AFTER applying a mutation so the error message
 * names the operation that introduced it. Cycles in the parent-edge
 * DAG break [deepContentHashOf]'s recursion and must never land on
 * disk.
 */
private fun Source.requireAcyclic(operation: String) {
    val cycle = cycleAt() ?: return
    val cycleStr = cycle.joinToString(" → ") { it.value }
    error(
        "$operation would introduce a cycle in the Source DAG: $cycleStr. " +
            "Source must remain acyclic — pick a different parent set, or " +
            "remove the offending edge first.",
    )
}
