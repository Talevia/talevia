package io.talevia.core.tool.builtin.source.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runDagSummaryQuery] — `source_query(select=
 * dag_summary)`, a structural overview row carrying nodeCount /
 * nodesByKind / roots / leaves / maxDepth / hotspots / orphans /
 * summaryText. Cycle 107 audit: 188 LOC, **zero** transitive test
 * references; the kdoc explicitly notes "Carried over verbatim from
 * the pre-merge `DescribeSourceDagTool` behavior" but no test
 * survived (or was newly written) when the tool consolidated.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Hotspot ordering: descending by transitiveClipCount, ascending
 *    by nodeId for ties.** A regression flipping the comparator would
 *    show low-impact nodes at the top of the hotspot list, defeating
 *    the dashboard's "what should I focus on first" purpose.
 *    `hotspotsRankedByTransitiveCountDescThenNodeIdAsc` pins both
 *    primary + tiebreaker.
 *
 * 2. **Orphans = nodes with no bound clips at all.** Hotspots are
 *    nodes WITH bound clips. Each node falls into exactly one
 *    bucket. A regression that listed a hotspot also as an orphan
 *    (or vice versa) would distort both counts. Pinned by
 *    `orphanAndHotspotAreMutuallyExclusivePartition`.
 *
 * 3. **maxDepth correctly handles diamond / cyclic / disconnected
 *    DAGs.** The DFS walks via childIndex with a `visited` cycle
 *    guard. A regression dropping the guard would stack-overflow
 *    on cycles; a regression mis-walking on diamonds would
 *    under-report depth (each path is independently deepest;
 *    diamond's longest path = 3 hops, not 2).
 */
class DagSummaryQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = binding,
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("v1"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes, revision = 42),
        )
    }

    private fun input(hotspotLimit: Int? = null) = SourceQueryTool.Input(
        select = SourceQueryTool.SELECT_DAG_SUMMARY,
        projectId = "p",
        hotspotLimit = hotspotLimit,
    )

    private fun decodeRow(out: SourceQueryTool.Output): DagSummaryRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(DagSummaryRow.serializer()),
            out.rows,
        ).single()

    // ── empty / trivial DAGs ──────────────────────────────────────

    @Test fun emptyDagReturnsZeroNodeRowAndEmptyGraphSummary() {
        val result = runDagSummaryQuery(project(emptyList()), input())
        val row = decodeRow(result.data)
        assertEquals(0, row.nodeCount)
        assertEquals(emptyMap(), row.nodesByKind)
        assertEquals(emptyList(), row.rootNodeIds)
        assertEquals(emptyList(), row.leafNodeIds)
        assertEquals(0, row.maxDepth)
        assertEquals(emptyList(), row.hotspots)
        assertEquals(emptyList(), row.orphanedNodeIds)
        // Pin the empty-graph summary text — operators see this when
        // they query a project before any source nodes are added.
        assertTrue(
            "0 nodes (empty graph)" in row.summaryText,
            "empty marker; got: ${row.summaryText}",
        )
    }

    @Test fun singleNodeDagHasOneRootOneLeafDepthOne() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "kind.x")
        val result = runDagSummaryQuery(project(listOf(a)), input())
        val row = decodeRow(result.data)
        assertEquals(1, row.nodeCount)
        assertEquals(listOf("a"), row.rootNodeIds)
        assertEquals(listOf("a"), row.leafNodeIds)
        assertEquals(1, row.maxDepth, "single-node graph has depth 1")
    }

    // ── DAG shape: linear chain ───────────────────────────────────

    @Test fun linearChainHasOneRootOneLeafAndDepthEqualsLength() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val result = runDagSummaryQuery(project(listOf(a, b, c)), input())
        val row = decodeRow(result.data)
        assertEquals(listOf("a"), row.rootNodeIds, "only a is a root (no parents)")
        assertEquals(listOf("c"), row.leafNodeIds, "only c is a leaf (no children)")
        assertEquals(3, row.maxDepth, "linear chain depth = nodeCount")
    }

    // ── DAG shape: diamond ────────────────────────────────────────

    @Test fun diamondShapeHasOneRootOneLeafCorrectDepth() {
        // a → b
        // a → c
        // b, c → d
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val result = runDagSummaryQuery(project(listOf(a, b, c, d)), input())
        val row = decodeRow(result.data)
        assertEquals(listOf("a"), row.rootNodeIds)
        assertEquals(listOf("d"), row.leafNodeIds)
        // Pin: diamond's longest path is 3 hops (a→b→d or a→c→d, both
        // length 3). NOT 2 — a regression that didn't walk every
        // child of every visited node would report 2.
        assertEquals(3, row.maxDepth)
    }

    // ── sorting invariants ────────────────────────────────────────

    @Test fun nodesByKindSortedAlphabetically() {
        // Pin: insertion order is z, a, m → output is a, m, z. UI
        // depends on this so re-renders don't shuffle counts.
        val nodes = listOf(
            SourceNode.create(id = SourceNodeId("z1"), kind = "z.kind"),
            SourceNode.create(id = SourceNodeId("a1"), kind = "a.kind"),
            SourceNode.create(id = SourceNodeId("m1"), kind = "m.kind"),
        )
        val result = runDagSummaryQuery(project(nodes), input())
        val row = decodeRow(result.data)
        assertEquals(listOf("a.kind", "m.kind", "z.kind"), row.nodesByKind.keys.toList())
    }

    @Test fun rootAndLeafIdsSortedAlphabetically() {
        // Pin: 3 isolated nodes (each is both root and leaf). Output
        // ids must be alphabetic regardless of insertion order.
        val nodes = listOf(
            SourceNode.create(id = SourceNodeId("zebra"), kind = "k"),
            SourceNode.create(id = SourceNodeId("alpha"), kind = "k"),
            SourceNode.create(id = SourceNodeId("mango"), kind = "k"),
        )
        val result = runDagSummaryQuery(project(nodes), input())
        val row = decodeRow(result.data)
        assertEquals(listOf("alpha", "mango", "zebra"), row.rootNodeIds)
        assertEquals(listOf("alpha", "mango", "zebra"), row.leafNodeIds)
        assertEquals(listOf("alpha", "mango", "zebra"), row.orphanedNodeIds)
    }

    // ── hotspot / orphan partition ────────────────────────────────

    @Test fun orphanAndHotspotAreMutuallyExclusivePartition() {
        // Pin: every node falls into EXACTLY ONE bucket. A node with
        // bound clips is a hotspot; without is an orphan. A regression
        // that listed a node in both lists would distort downstream
        // counts.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        // Clip binds to `a` — `a` is hotspot, `b` is orphan.
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runDagSummaryQuery(project(listOf(a, b), listOf(clip)), input())
        val row = decodeRow(result.data)
        val hotspotIds = row.hotspots.map { it.nodeId }.toSet()
        val orphanIds = row.orphanedNodeIds.toSet()
        assertEquals(setOf("a"), hotspotIds, "node 'a' is the hotspot")
        assertEquals(setOf("b"), orphanIds, "node 'b' is the orphan")
        assertTrue(
            (hotspotIds intersect orphanIds).isEmpty(),
            "no node may appear in both hotspots and orphans",
        )
    }

    @Test fun hotspotCarriesDirectAndTransitiveCounts() {
        // A clip directly bound to `b` (b is descendant of a):
        // - b is directlyBound (count = 1)
        // - a is transitively bound via `b` (direct = 0, transitive = 1)
        // Pin both fields so a regression collapsing them would
        // surface here.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("b"))) // bound to b only
        val result = runDagSummaryQuery(project(listOf(a, b), listOf(clip)), input())
        val row = decodeRow(result.data)
        val byNode = row.hotspots.associateBy { it.nodeId }
        // b: direct binding → directlyBound=true → directClipCount=1
        assertEquals(1, byNode.getValue("b").directClipCount)
        assertEquals(1, byNode.getValue("b").transitiveClipCount)
        // a: transitively reaches the same clip via b → direct=0
        assertEquals(0, byNode.getValue("a").directClipCount)
        assertEquals(1, byNode.getValue("a").transitiveClipCount)
    }

    @Test fun hotspotsRankedByTransitiveCountDescThenNodeIdAsc() {
        // a, b, c each get their own clip → transitive=1 each.
        // d gets two clips → transitive=2.
        // Pin: d first (highest transitive count), then a/b/c
        // alphabetical (tiebreaker = nodeId asc).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val d = SourceNode.create(id = SourceNodeId("d"), kind = "k")
        val clips = listOf(
            videoClip("c1", "asset-1", setOf(SourceNodeId("a"))),
            videoClip("c2", "asset-2", setOf(SourceNodeId("b"))),
            videoClip("c3", "asset-3", setOf(SourceNodeId("c"))),
            videoClip("c4", "asset-4", setOf(SourceNodeId("d"))),
            videoClip("c5", "asset-5", setOf(SourceNodeId("d"))),
        )
        val result = runDagSummaryQuery(project(listOf(a, b, c, d), clips), input())
        val row = decodeRow(result.data)
        // Pin descending by transitive, then ascending by nodeId.
        assertEquals(
            listOf("d", "a", "b", "c"),
            row.hotspots.map { it.nodeId },
            "hotspot order must be transitive-desc then nodeId-asc",
        )
        assertEquals(2, row.hotspots[0].transitiveClipCount)
    }

    @Test fun hotspotLimitCapsResultListLength() {
        // Pin: hotspotLimit=2 trims to 2 entries even when more nodes
        // qualify. Default limit is 5; passing 2 here verifies the
        // explicit override.
        val nodes = (1..7).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val clips = nodes.map { n ->
            videoClip("c-${n.id.value}", "asset-${n.id.value}", setOf(n.id))
        }
        val result = runDagSummaryQuery(project(nodes, clips), input(hotspotLimit = 2))
        val row = decodeRow(result.data)
        assertEquals(2, row.hotspots.size)
    }

    @Test fun hotspotLimitZeroProducesEmptyHotspotsButOrphansStillTracked() {
        // Edge case: limit=0 → no hotspots reported, but orphans (the
        // hotspot inverse) is independent of the limit. Pin the
        // separation.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k") // orphan
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runDagSummaryQuery(project(listOf(a, b), listOf(clip)), input(hotspotLimit = 0))
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.hotspots, "limit=0 → no hotspots reported")
        // Orphans unaffected — node b still listed.
        assertEquals(listOf("b"), row.orphanedNodeIds)
    }

    @Test fun negativeHotspotLimitClampsToZero() {
        // The function calls `coerceAtLeast(0)` on the limit. A
        // negative limit must NOT cause a take(-1) crash.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runDagSummaryQuery(project(listOf(a), listOf(clip)), input(hotspotLimit = -10))
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.hotspots, "negative limit clamps to 0")
    }

    // ── pluralisation in summary text ─────────────────────────────

    @Test fun summaryTextPluralisesRootsLeavesAndOrphansCorrectly() {
        // 1 node WITH a bound clip → "1 root", "1 leaf", no orphan suffix.
        // (orphan tail only fires when orphanCount > 0; without the clip,
        // even a lone node would count as an orphan.)
        val one = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val r1 = decodeRow(runDagSummaryQuery(project(listOf(one), listOf(clip)), input()).data)
        assertTrue("1 root" in r1.summaryText, "single root form; got: ${r1.summaryText}")
        assertTrue("1 leaf" in r1.summaryText, "single leaf form (NOT 'leaves'); got: ${r1.summaryText}")
        assertTrue("orphaned" !in r1.summaryText, "no orphan tail when count == 0; got: ${r1.summaryText}")

        // 2 nodes WITHOUT clips → both orphans → "2 roots", "2 leaves",
        // "2 orphaned nodes".
        val two = listOf(
            SourceNode.create(id = SourceNodeId("a"), kind = "k"),
            SourceNode.create(id = SourceNodeId("b"), kind = "k"),
        )
        val r2 = decodeRow(runDagSummaryQuery(project(two), input()).data)
        assertTrue("2 roots" in r2.summaryText, "plural roots; got: ${r2.summaryText}")
        assertTrue("2 leaves" in r2.summaryText, "plural leaves (NOT 'leafs' or '2 leafs'); got: ${r2.summaryText}")
        assertTrue("2 orphaned nodes" in r2.summaryText, "plural orphan; got: ${r2.summaryText}")
    }

    @Test fun summaryTextSingularOrphanIsOrphanedNode() {
        // Pin: 1 orphan → "1 orphaned node" singular. A regression
        // that always used plural ('s' suffix) would output a
        // grammatically wrong "1 orphaned nodes".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val row = decodeRow(runDagSummaryQuery(project(listOf(a, b), listOf(clip)), input()).data)
        assertTrue(
            "1 orphaned node." in row.summaryText,
            "singular 'orphaned node' (no trailing s); got: ${row.summaryText}",
        )
    }

    @Test fun summaryTextSingularClipPluralisesCorrectlyInHotspots() {
        // 1 clip on node `a` → "a → 1 clip" (singular). 2 clips →
        // "a → 2 clips" (plural). Pin both.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val singleClip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val rSingle = decodeRow(runDagSummaryQuery(project(listOf(a), listOf(singleClip)), input()).data)
        assertTrue("a → 1 clip" in rSingle.summaryText, "singular clip; got: ${rSingle.summaryText}")
        // 2-clip variant.
        val twoClips = listOf(
            videoClip("c1", "asset-1", setOf(SourceNodeId("a"))),
            videoClip("c2", "asset-2", setOf(SourceNodeId("a"))),
        )
        val rDouble = decodeRow(runDagSummaryQuery(project(listOf(a), twoClips), input()).data)
        assertTrue("a → 2 clips" in rDouble.summaryText, "plural clips; got: ${rDouble.summaryText}")
    }

    @Test fun summaryTextOmitsHotspotPartWhenNoneExist() {
        // All nodes are orphans → summary should NOT include "Top
        // hotspots:" prefix. Pin the negative case so a refactor that
        // unconditionally prepends an empty "Top hotspots: " gets caught.
        val nodes = listOf(
            SourceNode.create(id = SourceNodeId("a"), kind = "k"),
            SourceNode.create(id = SourceNodeId("b"), kind = "k"),
        )
        val row = decodeRow(runDagSummaryQuery(project(nodes), input()).data)
        assertTrue("Top hotspots" !in row.summaryText, "no hotspots → no header; got: ${row.summaryText}")
    }

    // ── Output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndSourceRevision() {
        // Pin the response envelope. sourceRevision = 42 (set in the
        // project's Source). UI cache invalidation depends on the
        // revision number for "is this dag_summary stale?".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDagSummaryQuery(project(listOf(a)), input())
        assertEquals(SourceQueryTool.SELECT_DAG_SUMMARY, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(42, result.data.sourceRevision, "sourceRevision must round-trip")
    }

    @Test fun titleIncludesNodeCountAndPluralisation() {
        // Pin: title "(N node(s))" — kdoc note about title format.
        val zero = runDagSummaryQuery(project(emptyList()), input())
        assertTrue(
            "(0 nodes)" in (zero.title ?: ""),
            "0-node title; got: ${zero.title}",
        )
        val one = runDagSummaryQuery(
            project(listOf(SourceNode.create(id = SourceNodeId("a"), kind = "k"))),
            input(),
        )
        assertTrue(
            "(1 node)" in (one.title ?: ""),
            "1-node title singular; got: ${one.title}",
        )
        val three = runDagSummaryQuery(
            project((1..3).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }),
            input(),
        )
        assertTrue(
            "(3 nodes)" in (three.title ?: ""),
            "3-node title plural; got: ${three.title}",
        )
    }
}
