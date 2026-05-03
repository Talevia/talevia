package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import io.talevia.core.util.fnv1a64Hex

/**
 * Fold a node's own [SourceNode.contentHash] together with the (ordered)
 * **deep** content hashes of every parent it references. The result is a
 * fingerprint that changes whenever **any ancestor** in the DAG changes —
 * not only when the node's own `(kind, body)` mutate.
 *
 * Why this exists (VISION §5.1 "显式标 stale"):
 *
 * `SourceNode.contentHash` is `contentHashOf(kind, body, parents)` — the
 * `parents` argument is the list of `SourceRef` **ids**, not their content.
 * So an edit to a `style_bible` grandparent node leaves every descendant's
 * `contentHash` byte-identical, and `ProjectStaleness.staleClipsFromLockfile`
 * — which only looks at the direct binding's shallow hash — silently misses
 * the change. Clips bound to the `character_ref` grandchild do not flag
 * stale, so `regenerate_stale_clips` ignores them.
 *
 * `deepContentHashOf` closes that loop by walking the DAG: each node's deep
 * hash folds its own `contentHash` + sorted-by-parent-id parent deep hashes,
 * so the transitive closure of "anything changed" surfaces at any depth.
 *
 * The walk is memoised per invocation via [cache] so a project with many
 * shared ancestors (typical: one `style_bible` parents five `character_ref`s)
 * pays O(nodes) not O(nodes × depth). A missing parent (dangling ref) is
 * folded as the sentinel `missing:<id>` so the deep hash stays deterministic
 * even when the DAG is mid-edit; `project_query(select=validation)` is the lane that
 * surfaces the dangling-ref issue separately.
 *
 * Kept deliberately as a free function on [Source] rather than a computed
 * property on [SourceNode] because the recursion requires the sibling index
 * — a method on `SourceNode` in isolation cannot walk the DAG.
 */
fun Source.deepContentHashOf(
    nodeId: SourceNodeId,
    cache: MutableMap<SourceNodeId, String> = mutableMapOf(),
    inProgress: MutableSet<SourceNodeId> = mutableSetOf(),
): String {
    cache[nodeId]?.let { return it }
    val node = byId[nodeId] ?: run {
        // Dangling ref: fold a stable sentinel so partial DAGs still hash
        // deterministically. project_query(select=validation) reports dangling refs
        // through its own warning channel.
        val sentinel = "missing:${nodeId.value}"
        cache[nodeId] = sentinel
        return sentinel
    }

    // Cycle defense: if we're already computing this node's hash up the
    // call stack, fold a stable "cycle:<id>" sentinel and bail. The
    // mutation guards in SourceMutations reject cycle-introducing writes,
    // but on-disk data from older builds (or hand-edited talevia.json)
    // might still carry one — recursion without this would stack-overflow.
    if (nodeId in inProgress) {
        val sentinel = "cycle:${nodeId.value}"
        // Don't cache the sentinel — the same id under a different
        // ancestor walk should produce its real hash. Only the current
        // back-edge sees the sentinel.
        return sentinel
    }
    inProgress += nodeId

    // Canonical parent projection: sort by id so the `parents=` portion of
    // the fold doesn't change with list-order permutations. Note this only
    // stabilises the parents= projection — the deep hash overall remains
    // **order-sensitive over parents** because the `shallow=${node.contentHash}`
    // term immediately below also folds in, and shallow IS order-sensitive
    // (it hashes the serialised `parents: List<SourceRef>` verbatim). A
    // reordered parents list therefore yields a different shallow → different
    // deep. This is acceptable for the staleness-cascade use case: re-ordering
    // parents over-invalidates (the descendant's deep hash changes even
    // though no upstream content actually changed) which is the safe
    // direction — `regenerate_stale_clips` will re-run a clip whose only
    // change is parent-order, which is conservative but correct. The sort
    // here keeps the parents= projection deterministic so two runs over the
    // same DAG produce the same hash, even as the cycle / cache state
    // varies. See `DeepContentHashTest.deepHashIsOrderSensitiveOverParents…`.
    val parentHashes = node.parents
        .map { it.nodeId }
        .sortedBy { it.value }
        .joinToString(separator = "|") { "${it.value}=${deepContentHashOf(it, cache, inProgress)}" }

    val folded = fnv1a64Hex("shallow=${node.contentHash}|parents=$parentHashes")
    cache[nodeId] = folded
    inProgress -= nodeId
    return folded
}
