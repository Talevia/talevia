package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId

/**
 * Pure, value-level mutations on [Source]. Each returns a new [Source] with:
 *  - the [Source]'s [Source.revision] bumped by one,
 *  - the touched node's [SourceNode.revision] bumped by one,
 *  - the touched node's [SourceNode.contentHash] recomputed from the new revision
 *    (stubbed — see `TODO(DAG)` in [SourceNode]).
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
    return copy(
        revision = revision + 1,
        nodes = nodes + prepared,
    )
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
    return copy(
        revision = revision + 1,
        nodes = nodes.toMutableList().also { it[index] = bumped },
    )
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
 * Internal: bump a node's [SourceNode.revision] + recompute [SourceNode.contentHash].
 * Kept private to this file so genre code cannot forget to bump on write — all writes
 * go through [addNode] / [replaceNode].
 */
private fun SourceNode.bumpedForWrite(): SourceNode {
    val nextRev = revision + 1
    // TODO(DAG): recompute contentHash from (kind, body, parents); today we mirror revision.
    return copy(revision = nextRev, contentHash = nextRev.toString())
}
