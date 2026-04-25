package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [deepContentHashOf]'s `inProgress` set is the second line of defence
 * — the mutation guards in [SourceMutations] reject cycle-introducing
 * writes, so production data stays acyclic. But on-disk
 * `talevia.json` from older builds (or hand-edited files) might still
 * carry a cycle; without the in-progress guard, the recursion would
 * stack-overflow on first read.
 *
 * Constructs a cyclic [Source] by bypassing [Source.addNode] /
 * [Source.replaceNode] (using the raw `Source(...)` constructor), then
 * verifies `deepContentHashOf` terminates with a stable
 * `cycle:<id>` sentinel rather than infinite-recursing.
 */
class SourceDeepContentHashCycleSafetyTest {

    private fun nid(s: String) = SourceNodeId(s)

    private fun node(id: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = nid(id),
            kind = "test",
            parents = parents.map { SourceRef(nid(it)) },
        )

    @Test fun selfCycleProducesStableSentinelInsteadOfStackOverflow() {
        // a -> a — direct self-cycle. Must terminate.
        val source = Source(nodes = listOf(node("a", parents = listOf("a"))))
        val hash = source.deepContentHashOf(nid("a"))
        // No assertion on hash content; we only care that it terminates
        // and is non-empty.
        assertTrue(hash.isNotBlank())
        // Determinism: two consecutive calls must produce the same hash.
        // The first call uses an empty cache; sentinel handling must not
        // pollute the cache in a way that flips this on the second call.
        val secondCallCache = mutableMapOf<SourceNodeId, String>()
        val secondHash = source.deepContentHashOf(nid("a"), secondCallCache)
        assertEquals(hash, secondHash)
    }

    @Test fun mutualCycleTwoNodesTerminates() {
        // a -> b -> a
        val source = Source(
            nodes = listOf(
                node("a", parents = listOf("b")),
                node("b", parents = listOf("a")),
            ),
        )
        val hashA = source.deepContentHashOf(nid("a"))
        val hashB = source.deepContentHashOf(nid("b"))
        // Both terminate without stack overflow. They differ because
        // shallow contentHashes differ (different parents lists).
        assertTrue(hashA.isNotBlank())
        assertTrue(hashB.isNotBlank())
    }

    @Test fun longCycleTerminates() {
        // 50-node chain a0 -> a1 -> ... -> a49 -> a0 — long enough to
        // stack-overflow on a naive recursive implementation if the
        // guard weren't there.
        val n = 50
        val nodes = (0 until n).map { i ->
            val nextId = "a${(i + 1) % n}"
            node("a$i", parents = listOf(nextId))
        }
        val source = Source(nodes = nodes)
        val hash = source.deepContentHashOf(nid("a0"))
        assertTrue(hash.isNotBlank())
    }
}
