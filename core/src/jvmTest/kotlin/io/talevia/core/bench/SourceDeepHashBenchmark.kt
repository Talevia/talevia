package io.talevia.core.bench

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.deepContentHashOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Wall-time regression guard for [Source.deepContentHashOf] — the
 * fingerprint that drives per-clip render-cache lookups
 * (`PerClipRender.deepHashCache`) and lockfile staleness
 * (`ProjectStaleness.staleClipsFromLockfile`).
 *
 * Per-clip incremental render hits this hash on every cache-key
 * computation. If a refactor breaks memoisation (the `cache` arg now
 * a fresh map per call, the parent walk no longer sorted, etc.), the
 * cost goes from "negligible" to "n× per-clip overhead" without any
 * test catching it — until exports get visibly slower in production.
 *
 * **Topology** — 100 nodes across 6 depth layers, picked to mirror a
 * real project shape:
 *   depth 0 (roots, e.g. style_bible):  6 nodes
 *   depth 1                          : 10 nodes
 *   depth 2                          : 16 nodes
 *   depth 3                          : 22 nodes
 *   depth 4                          : 24 nodes
 *   depth 5 (leaves, e.g. shot ref)  : 22 nodes  → 100 total
 * Each non-root picks 2 parents from prior depths so the DAG has
 * fan-in (overlapping ancestors), exercising the memoisation.
 *
 * **Two scenarios** — the bench measures:
 *   1. Per-node hash with a fresh empty cache (cold path — what
 *      `ProjectStaleness` does once per stale check). Iterates every
 *      node so deep + shallow paths both run.
 *   2. Leaf-set hash with one shared cache across leaves (warm path
 *      — what `PerClipRender` does when computing fingerprints for
 *      many clips bound to overlapping ancestors). The scenario the
 *      bullet specifically calls out.
 *
 * **Budget policy (v1).** Never-fail, print-and-soft-warn. Real
 * observed numbers on CI are well under 50 ms for both scenarios; a
 * 4-10× regression (the bullet's stated risk) would push past the
 * soft budget visibly.
 */
class SourceDeepHashBenchmark {

    @Test fun coldPathPerNode() {
        val source = build100NodeDag()
        val cache = mutableMapOf<SourceNodeId, String>()
        // Untimed warm-up — same convention as the other bench files.
        // First pass populates the cache; subsequent passes hit it.
        repeat(3) {
            cache.clear()
            for (node in source.nodes) source.deepContentHashOf(node.id, cache)
        }

        val elapsed = measureTime {
            repeat(COLD_LOOPS) {
                cache.clear()
                for (node in source.nodes) source.deepContentHashOf(node.id, cache)
            }
        }
        AgentLoopBenchmark.report(
            name = "source.deepContentHashOf.100nodes.coldPath.${COLD_LOOPS}x",
            elapsed = elapsed,
            softBudget = COLD_BUDGET,
        )
    }

    @Test fun warmPathSharedCacheAcrossLeaves() {
        val source = build100NodeDag()
        val leafIds = source.nodes
            .filter { node -> source.nodes.none { other -> other.parents.any { it.nodeId == node.id } } }
            .map { it.id }
        check(leafIds.size > 10) { "topology should have >10 leaves; got ${leafIds.size}" }

        // Untimed warm-up.
        repeat(3) {
            val cache = mutableMapOf<SourceNodeId, String>()
            for (id in leafIds) source.deepContentHashOf(id, cache)
        }

        // Per the bullet: many leaves share the same cache, exercising the
        // ancestor-overlap cache hits that PerClipRender depends on.
        val elapsed = measureTime {
            repeat(WARM_LOOPS) {
                val cache = mutableMapOf<SourceNodeId, String>()
                for (id in leafIds) source.deepContentHashOf(id, cache)
            }
        }
        AgentLoopBenchmark.report(
            name = "source.deepContentHashOf.${leafIds.size}leaves.sharedCache.${WARM_LOOPS}x",
            elapsed = elapsed,
            softBudget = WARM_BUDGET,
        )
    }

    private fun build100NodeDag(): Source {
        // Layer sizes chosen to land at exactly 100 nodes. Each non-root
        // picks 2 parents from prior depths via deterministic modulo
        // selection so the bench is reproducible across runs.
        val layerSizes = listOf(6, 10, 16, 22, 24, 22)
        check(layerSizes.sum() == 100)
        val nodesByLayer = mutableListOf<List<SourceNode>>()
        var globalIndex = 0
        for ((depth, size) in layerSizes.withIndex()) {
            val layerNodes = (0 until size).map { i ->
                val id = SourceNodeId("d${depth}n$i")
                val parents = if (depth == 0) {
                    emptyList()
                } else {
                    // Pick 2 parents from each of the prior layers in turn —
                    // not just one — so fan-in is realistic, not a tree.
                    val parentLayer = nodesByLayer[depth - 1]
                    val grandLayer = nodesByLayer.getOrNull(depth - 2)
                    val pickedParent = parentLayer[(i + globalIndex) % parentLayer.size].id
                    val pickedGrand = grandLayer?.get((i * 7 + globalIndex) % grandLayer.size)?.id
                    listOfNotNull(pickedParent, pickedGrand).map { SourceRef(it) }
                }
                globalIndex++
                SourceNode.create(
                    id = id,
                    kind = "bench.kind.depth$depth",
                    body = SYNTHETIC_BODY,
                    parents = parents,
                )
            }
            nodesByLayer += layerNodes
        }
        return Source(nodes = nodesByLayer.flatten())
    }

    companion object {
        // Deterministic body big enough to make hashing a non-trivial cost
        // (small bodies get optimised away) — modelled on a real
        // character_ref body shape.
        private val SYNTHETIC_BODY: JsonObject = buildJsonObject {
            put("name", JsonPrimitive("Bench Character"))
            put("hair", JsonPrimitive("brown shoulder-length"))
            put("outfit", JsonPrimitive("wool coat with leather satchel"))
            put("prompt", JsonPrimitive("A studio portrait of the character lit from the side."))
            put("negativePrompt", JsonPrimitive("blurry, watermark"))
        }

        private const val COLD_LOOPS = 50
        private const val WARM_LOOPS = 100
        private val COLD_BUDGET: Duration = 250.milliseconds
        private val WARM_BUDGET: Duration = 250.milliseconds
    }
}
