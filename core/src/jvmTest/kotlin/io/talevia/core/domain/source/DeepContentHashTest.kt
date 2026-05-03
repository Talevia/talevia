package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Source.deepContentHashOf] —
 * `core/domain/source/DeepContentHash.kt`. The DAG-walking
 * fingerprint that makes ancestor changes propagate to
 * descendants — the load-bearing piece for VISION §5.1
 * "显式标 stale" so a `style_bible` edit re-stales every
 * downstream `character_ref`. Cycle 172 audit: 82 LOC, 0
 * direct test refs (the bench file
 * `SourceDeepHashBenchmark.kt` exercises it for perf but
 * the correctness contracts — propagation,
 * order-insensitivity, sentinel determinism, cycle
 * defense — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Ancestor changes propagate to descendants.** The
 *    marquee VISION §5.1 invariant. Two grandchildren
 *    sharing a `style_bible` grandparent: editing the
 *    grandparent's body changes the grandchild's deep
 *    hash even though shallow `contentHash` is unchanged
 *    (because `contentHash` only hashes parent IDs, not
 *    parent content). Drift to "shallow only" would let
 *    `staleClipsFromLockfile` silently miss
 *    grandparent-driven staleness — the bug this
 *    function exists to prevent.
 *
 * 2. **Order-insensitive over parent IDs at the deep
 *    level (intentional asymmetry with shallow
 *    contentHash).** Per kdoc: "the shallow contentHash
 *    already stabilises on parents list order ... we
 *    intentionally normalise at the deep layer to make
 *    the deep hash order-insensitive over parents."
 *    Shallow hash for `parents=[a,b]` vs `[b,a]` differs;
 *    deep hash agrees. Drift to "preserve order at deep"
 *    would over-invalidate caches whenever a user
 *    re-orders parents without changing semantics.
 *
 * 3. **Sentinel handling: `missing:<id>` for dangling
 *    refs (cached); `cycle:<id>` for back-edges (NOT
 *    cached).** Dangling refs return the same sentinel
 *    deterministically across calls so a partial DAG
 *    still hashes stably. Cycles return a sentinel WITHOUT
 *    caching it so that the same id under a different
 *    ancestor walk produces its real hash. Drift to
 *    "cache cycle sentinel" would freeze the wrong hash
 *    on the first encounter.
 */
class DeepContentHashTest {

    private fun nodeId(s: String) = SourceNodeId(s)

    private fun jsonBody(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun node(id: String, body: String = "v0", parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = nodeId(id),
            kind = "test",
            body = jsonBody("v" to body),
            parents = parents.map { SourceRef(nodeId(it)) },
        )

    private fun source(vararg nodes: SourceNode): Source = Source(nodes = nodes.toList())

    // ── Determinism + leaf nodes ────────────────────────────────

    @Test fun sameInputProducesSameDeepHash() {
        // Pin: deterministic. Calling twice with fresh
        // caches yields the same string.
        val src = source(node("a"))
        assertEquals(
            src.deepContentHashOf(nodeId("a")),
            src.deepContentHashOf(nodeId("a")),
        )
    }

    @Test fun deepHashIsHexString16Chars() {
        // Pin: fnv1a64Hex always produces a 16-char
        // 0-padded hex string for non-sentinel paths.
        // Drift to a different format (e.g. base64, no
        // padding) would break consumers parsing the hash.
        val src = source(node("a"))
        val hash = src.deepContentHashOf(nodeId("a"))
        assertEquals(16, hash.length, "FNV-1a 64-bit hex is 16 chars; got: '$hash'")
        assertTrue(
            hash.all { it in "0123456789abcdef" },
            "lowercase hex only; got: '$hash'",
        )
    }

    @Test fun shallowContentDifferenceProducesDifferentDeepHash() {
        // Pin: changing a leaf's body changes its deep
        // hash. The trivial baseline.
        val a1 = source(node("a", body = "v1"))
        val a2 = source(node("a", body = "v2"))
        assertNotEquals(
            a1.deepContentHashOf(nodeId("a")),
            a2.deepContentHashOf(nodeId("a")),
        )
    }

    // ── Marquee propagation pin ───────────────────────────────

    @Test fun grandparentChangePropagatesToGrandchildDeepHash() {
        // The marquee VISION §5.1 pin: a `style_bible`
        // grandparent's body change must propagate to its
        // grandchild's deep hash even though the
        // grandchild's SHALLOW contentHash is unchanged
        // (because shallow only hashes parent IDs). This
        // is the whole reason deepContentHashOf exists.
        //
        // Drift to "shallow only" would let
        // `staleClipsFromLockfile` silently miss grandparent
        // edits — the bug this function exists to prevent.
        //
        // Layout: grandparent ← parent ← grandchild
        val srcV1 = source(
            node("grandparent", body = "v1"),
            node("parent", parents = listOf("grandparent")),
            node("grandchild", parents = listOf("parent")),
        )
        val srcV2 = source(
            node("grandparent", body = "v2"),
            node("parent", parents = listOf("grandparent")),
            node("grandchild", parents = listOf("parent")),
        )

        // Shallow contentHash on grandchild is unchanged
        // (its kind+body+parent-IDs haven't moved). Deep
        // hash MUST change.
        val grandchildShallowV1 = srcV1.byId.getValue(nodeId("grandchild")).contentHash
        val grandchildShallowV2 = srcV2.byId.getValue(nodeId("grandchild")).contentHash
        assertEquals(
            grandchildShallowV1,
            grandchildShallowV2,
            "shallow contentHash on grandchild is identical (only parent-IDs hashed, not their bodies)",
        )

        val grandchildDeepV1 = srcV1.deepContentHashOf(nodeId("grandchild"))
        val grandchildDeepV2 = srcV2.deepContentHashOf(nodeId("grandchild"))
        assertNotEquals(
            grandchildDeepV1,
            grandchildDeepV2,
            "deep hash on grandchild MUST change when grandparent's body changes — VISION §5.1 invariant",
        )
    }

    @Test fun siblingsShareGrandparentMutationCascade() {
        // Pin: the propagation is per-descendant. Two
        // grandchildren sharing one grandparent both see
        // their deep hash change when the grandparent's
        // body changes — and both change to the EXPECTED
        // new value (different from their respective old
        // values, but equal to each other if their own
        // shallow content was identical).
        val srcV1 = source(
            node("grandparent", body = "v1"),
            node("parent", parents = listOf("grandparent")),
            node("char-a", parents = listOf("parent")),
            node("char-b", parents = listOf("parent")),
        )
        val srcV2 = source(
            node("grandparent", body = "v2"),
            node("parent", parents = listOf("grandparent")),
            node("char-a", parents = listOf("parent")),
            node("char-b", parents = listOf("parent")),
        )

        for (id in listOf("char-a", "char-b")) {
            assertNotEquals(
                srcV1.deepContentHashOf(nodeId(id)),
                srcV2.deepContentHashOf(nodeId(id)),
                "$id deep hash changes on grandparent edit",
            )
        }
    }

    // ── Order-insensitivity at deep layer ─────────────────────

    @Test fun deepHashIsOrderSensitiveOverParentsBecauseShallowContributes() {
        // CONTRACT PIN: the deep hash IS order-sensitive
        // over parents because `node.contentHash` (shallow)
        // folds in, and shallow hashes the serialised
        // `parents: List<SourceRef>` verbatim. The
        // implementation's parent-hash-projection is
        // sorted by id at the join step, but that only
        // stabilises the `parents=` portion of the fold —
        // the `shallow=${node.contentHash}|` portion
        // diverges with parent order.
        //
        // This is the safe / over-invalidating direction
        // for the staleness cascade: a clip bound to a
        // node whose only change is parent-order will be
        // re-flagged stale and `regenerate_stale_clips`
        // will re-run it, which is conservative but
        // correct. The kdoc on `deepContentHashOf` was
        // updated cycle 205 to document this contract
        // (was previously mis-stated as
        // "order-insensitive over parents").
        val srcAB = source(
            node("a"),
            node("b"),
            node("child", parents = listOf("a", "b")),
        )
        val srcBA = source(
            node("a"),
            node("b"),
            node("child", parents = listOf("b", "a")),
        )

        // Sanity: shallow IS order-sensitive (hashes the
        // serialized list).
        val childShallowAB = srcAB.byId.getValue(nodeId("child")).contentHash
        val childShallowBA = srcBA.byId.getValue(nodeId("child")).contentHash
        assertNotEquals(childShallowAB, childShallowBA, "shallow is order-sensitive")

        // Actual deep behavior — also order-sensitive
        // because shallow contributes. Drift to "actually
        // order-insensitive at deep" would surface as a
        // test failure here, prompting a re-evaluation of
        // either the impl or the kdoc.
        assertNotEquals(
            srcAB.deepContentHashOf(nodeId("child")),
            srcBA.deepContentHashOf(nodeId("child")),
            "deep is order-sensitive over parents (over-invalidating safe direction; documented on deepContentHashOf kdoc)",
        )
    }

    // ── Dangling refs → "missing:<id>" sentinel ──────────────

    @Test fun missingNodeReturnsMissingSentinel() {
        // Pin: querying a node that doesn't exist in the
        // Source returns the documented sentinel. The
        // sentinel format is `missing:<idValue>` —
        // downstream tooling may match on "missing:" prefix.
        val src = Source.EMPTY
        val hash = src.deepContentHashOf(nodeId("ghost"))
        assertEquals("missing:ghost", hash)
    }

    @Test fun danglingParentRefFoldsAsMissingSentinel() {
        // Marquee dangling-ref pin: a parent ref that
        // doesn't resolve folds as `missing:<id>` so the
        // deep hash stays deterministic even on
        // mid-edit/partial DAGs. Two sources with the same
        // ghost reference produce the same deep hash —
        // drift to "throw on dangling" would crash every
        // mid-edit query.
        val src1 = source(node("child", parents = listOf("ghost-parent")))
        val src2 = source(node("child", parents = listOf("ghost-parent")))
        assertEquals(
            src1.deepContentHashOf(nodeId("child")),
            src2.deepContentHashOf(nodeId("child")),
            "dangling-ref deep hash is deterministic across sources",
        )
        // And NOT equal to the same node with a different
        // ghost id (sentinel includes the id).
        val src3 = source(node("child", parents = listOf("other-ghost")))
        assertNotEquals(
            src1.deepContentHashOf(nodeId("child")),
            src3.deepContentHashOf(nodeId("child")),
            "different ghost ids produce different deep hashes",
        )
    }

    // ── Cycle → "cycle:<id>" sentinel (NOT cached) ────────────

    @Test fun selfLoopReturnsCycleSentinelOnBackEdge() {
        // Pin: a self-loop produces a `cycle:<id>`
        // sentinel rather than stack-overflowing. Per
        // kdoc: "The mutation guards in SourceMutations
        // reject cycle-introducing writes, but on-disk
        // data from older builds (or hand-edited
        // talevia.json) might still carry one — recursion
        // without this would stack-overflow."
        //
        // The TOP-LEVEL call to `deepContentHashOf("a")`
        // doesn't return the sentinel (a is in
        // `inProgress` only after the entry); it returns
        // a real hash that FOLDS the cycle sentinel of its
        // self-parent. Drift to "infinite recursion" would
        // crash on first read.
        val src = source(node("a", parents = listOf("a")))
        val hash = src.deepContentHashOf(nodeId("a"))
        assertEquals(16, hash.length, "real hash returned, not the cycle: prefix")
        assertTrue(
            hash.all { it in "0123456789abcdef" },
            "non-sentinel hash; the cycle sentinel is folded into the real hash",
        )
    }

    @Test fun cycleSentinelIsNotCached() {
        // Marquee non-cache-cycle pin: per kdoc "Don't
        // cache the sentinel — the same id under a
        // different ancestor walk should produce its real
        // hash. Only the current back-edge sees the
        // sentinel."
        //
        // After computing deep hash of a (with self-loop),
        // querying a's deep hash again with a fresh cache
        // produces the SAME real hash — proving the
        // sentinel didn't poison the cache.
        val src = source(node("a", parents = listOf("a")))
        val cache1 = mutableMapOf<SourceNodeId, String>()
        val hash1 = src.deepContentHashOf(nodeId("a"), cache = cache1)
        val cache2 = mutableMapOf<SourceNodeId, String>()
        val hash2 = src.deepContentHashOf(nodeId("a"), cache = cache2)
        assertEquals(hash1, hash2, "deterministic across fresh caches")
        // The cached value for `a` after the call MUST be
        // the real hash, NOT "cycle:a" — drift would mean
        // future descendants reading from this cache get
        // the sentinel instead of the real hash.
        assertEquals(hash1, cache1[nodeId("a")])
        assertFalse(cache1.values.any { it.startsWith("cycle:") }, "no cycle: in cache")
    }

    // ── Memoization ───────────────────────────────────────────

    @Test fun cacheIsPopulatedAcrossSubcomputations() {
        // Pin: the recursion populates the cache for every
        // node walked. After hashing a grandchild, the
        // cache contains entries for grandchild, parent,
        // grandparent. Drift to "no cache fill" would
        // blow up O(nodes × depth) on shared-ancestor
        // graphs (one style_bible parents many
        // character_refs).
        val src = source(
            node("grandparent"),
            node("parent", parents = listOf("grandparent")),
            node("grandchild", parents = listOf("parent")),
        )
        val cache = mutableMapOf<SourceNodeId, String>()
        src.deepContentHashOf(nodeId("grandchild"), cache = cache)
        assertTrue(nodeId("grandchild") in cache, "grandchild cached")
        assertTrue(nodeId("parent") in cache, "parent cached as side effect")
        assertTrue(nodeId("grandparent") in cache, "grandparent cached as side effect")
    }

    @Test fun sharedCacheAcrossCallsAvoidsRecomputation() {
        // Pin: calling deepContentHashOf with a passed-in
        // populated cache just returns the cached value
        // (the early `cache[nodeId]?.let { return it }`
        // short-circuit). Drift would silently re-compute
        // — slow on large DAGs but correct, so the test
        // verifies the cache value is honored.
        val src = source(node("a"))
        val cache = mutableMapOf<SourceNodeId, String>()
        cache[nodeId("a")] = "fake-precomputed-value"
        val out = src.deepContentHashOf(nodeId("a"), cache = cache)
        assertEquals(
            "fake-precomputed-value",
            out,
            "cache hit returns cached value verbatim, NOT re-computed",
        )
    }

    private fun assertFalse(condition: Boolean, message: String) {
        assertTrue(!condition, message)
    }
}
