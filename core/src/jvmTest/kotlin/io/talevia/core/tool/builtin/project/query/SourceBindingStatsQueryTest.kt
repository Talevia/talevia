package io.talevia.core.tool.builtin.project.query

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
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runSourceBindingStatsQuery] —
 * `project_query(select=source_binding_stats)`. Per-kind
 * coverage picture: how many nodes of each kind are bound to
 * clips (directly / transitively) vs orphan. Cycle 138 audit:
 * 140 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Three buckets partition `totalNodes` exactly:
 *    boundDirectly + boundTransitively + orphans == totalNodes.**
 *    Per kdoc: "the three categories partition the nodes of
 *    this kind." Plus the explicit "directly bound" semantic:
 *    a node bound directly is NOT counted in
 *    boundTransitively (even if it ALSO has bound descendants).
 *    A regression letting a node count in two buckets would
 *    silently inflate coverage; missing one would silently
 *    underreport.
 *
 * 2. **`directly` wins over `transitive` when both apply.** Per
 *    code: `reports.any { it.directlyBound }` triggers direct
 *    bucket; only nodes with reports but no direct binding go
 *    to transitive. Test plants a node bound directly AND has
 *    a directly-bound descendant — verifies the parent counts
 *    in `boundDirectly`, NOT `boundTransitively`.
 *
 * 3. **`coverageRatio` is `(direct + transitive) / total`,
 *    0.0..1.0.** Excluded: orphans don't count as covered.
 *    Edge: total=0 → 0.0 (div-by-zero clamp). The LLM uses
 *    this to surface "stylebible coverage 4/12 = 33%" — silent
 *    drift in the math would corrupt utilisation advice.
 */
class SourceBindingStatsQueryTest {

    private val timeRange = TimeRange(start = kotlin.time.Duration.ZERO, duration = 5.seconds)

    private fun videoClip(id: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId("a-$id"),
        sourceBinding = binding,
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList()
        else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes),
        )
    }

    private fun decodeRows(out: ProjectQueryTool.Output): List<SourceBindingStatsRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceBindingStatsRow.serializer()),
            out.rows,
        )

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyProjectReturnsZeroAndDedicatedMarker() {
        val result = runSourceBindingStatsQuery(project(emptyList()), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(emptyList(), decodeRows(result.data))
        assertTrue(
            "Project p has no source nodes." in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    // ── 3-bucket partition ───────────────────────────────────────

    @Test fun directBoundNodeBucketsToDirectNotTransitive() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a), listOf(clip)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals("k", row.kind)
        assertEquals(1, row.totalNodes)
        assertEquals(1, row.boundDirectly)
        assertEquals(0, row.boundTransitively, "direct ≠ transitive")
        assertEquals(0, row.orphans)
    }

    @Test fun transitiveOnlyNodeBucketsToTransitive() {
        // a → b. clip binds to b directly. From a's perspective:
        // clipsBoundTo(a) returns the clip with directlyBound=
        // false (binding is via descendant b). a goes to
        // transitive bucket; b goes to direct.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val clip = videoClip("c1", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a, b), listOf(clip)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(2, row.totalNodes)
        assertEquals(1, row.boundDirectly, "b directly bound")
        assertEquals(1, row.boundTransitively, "a transitively bound (via b)")
        assertEquals(0, row.orphans)
    }

    @Test fun nodeWithNoReportsBucketsToOrphans() {
        // No clips bind a OR any descendant.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(1, row.totalNodes)
        assertEquals(0, row.boundDirectly)
        assertEquals(0, row.boundTransitively)
        assertEquals(1, row.orphans)
        assertEquals(listOf("a"), row.orphanNodeIds, "orphan id surfaces")
    }

    @Test fun threeBucketsPartitionTotalNodesExactly() {
        // Pin: direct + transitive + orphans == totalNodes for
        // every kind. Construct 3 nodes of same kind covering all
        // 3 buckets.
        val direct = SourceNode.create(id = SourceNodeId("direct"), kind = "k")
        val parent = SourceNode.create(id = SourceNodeId("parent"), kind = "k")
        val descendant = SourceNode.create(
            id = SourceNodeId("descendant"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("parent"))),
        )
        val orphan = SourceNode.create(id = SourceNodeId("orphan"), kind = "k")
        // Clip binds direct + descendant.
        val clip = videoClip("c", setOf(SourceNodeId("direct"), SourceNodeId("descendant")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(
                project(listOf(direct, parent, descendant, orphan), listOf(clip)),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals(4, row.totalNodes, "4 nodes of kind k")
        assertEquals(2, row.boundDirectly, "direct + descendant")
        assertEquals(1, row.boundTransitively, "parent (transitive via descendant)")
        assertEquals(1, row.orphans, "orphan")
        // Pin: partition holds.
        assertEquals(
            row.totalNodes,
            row.boundDirectly + row.boundTransitively + row.orphans,
            "buckets partition total exactly",
        )
    }

    // ── direct wins over transitive when both apply ─────────────

    @Test fun nodeBoundDirectlyAndHasBoundDescendantCountsAsDirectOnly() {
        // The marquee precedence pin: per code, `reports.any
        // { it.directlyBound }` triggers direct bucket — even
        // if the node ALSO has bound descendants. A regression
        // counting it in both would inflate transitive +
        // double-count.
        // a → b. Clip binds BOTH a (directly) AND b (also).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val clip = videoClip("c", setOf(SourceNodeId("a"), SourceNodeId("b")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a, b), listOf(clip)), 100, 0).data,
        )
        val row = rows.single()
        // Pin: a is in direct (NOT transitive even though b is
        // also bound).
        assertEquals(2, row.boundDirectly, "both a and b directly bound")
        assertEquals(0, row.boundTransitively, "no node falls to transitive")
    }

    // ── coverageRatio math ────────────────────────────────────────

    @Test fun coverageRatioFullWhenAllNodesBound() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val c1 = videoClip("c1", setOf(SourceNodeId("a")))
        val c2 = videoClip("c2", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a, b), listOf(c1, c2)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(1.0, row.coverageRatio)
    }

    @Test fun coverageRatioPartialWithMixedBuckets() {
        // 1 direct + 0 transitive + 1 orphan / 2 total = 0.5.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c", setOf(SourceNodeId("a")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a, b), listOf(clip)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(0.5, row.coverageRatio)
    }

    @Test fun coverageRatioZeroWhenAllOrphans() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(a)), 100, 0).data,
        )
        assertEquals(0.0, rows.single().coverageRatio)
    }

    // ── per-kind grouping + sort ──────────────────────────────────

    @Test fun multipleKindsGroupedSeparately() {
        val charA = SourceNode.create(id = SourceNodeId("c1"), kind = "character_ref")
        val charB = SourceNode.create(id = SourceNodeId("c2"), kind = "character_ref")
        val style = SourceNode.create(id = SourceNodeId("s1"), kind = "style_bible")
        val clip = videoClip("c", setOf(SourceNodeId("c1")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(
                project(listOf(charA, charB, style), listOf(clip)),
                100,
                0,
            ).data,
        )
        // Pin: 2 kinds → 2 rows.
        assertEquals(2, rows.size)
        val byKind = rows.associateBy { it.kind }
        assertEquals(2, byKind.getValue("character_ref").totalNodes)
        assertEquals(1, byKind.getValue("character_ref").boundDirectly)
        assertEquals(1, byKind.getValue("style_bible").totalNodes)
        assertEquals(1, byKind.getValue("style_bible").orphans)
    }

    @Test fun rowsSortedAlphabeticallyByKind() {
        // Pin: insertion order [zebra, alpha, mango] →
        // alphabetical [alpha, mango, zebra].
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "zebra")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "alpha")
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "mango")
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(z, a, m)), 100, 0).data,
        )
        assertEquals(listOf("alpha", "mango", "zebra"), rows.map { it.kind })
    }

    // ── orphanNodeIds sorted ──────────────────────────────────────

    @Test fun orphanNodeIdsAreSortedAlphabetically() {
        val z = SourceNode.create(id = SourceNodeId("zebra"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("alpha"), kind = "k")
        val m = SourceNode.create(id = SourceNodeId("mango"), kind = "k")
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(z, a, m)), 100, 0).data,
        )
        assertEquals(listOf("alpha", "mango", "zebra"), rows.single().orphanNodeIds)
    }

    @Test fun nonOrphanNodesNotInOrphanNodeIdsList() {
        val direct = SourceNode.create(id = SourceNodeId("direct"), kind = "k")
        val orphan = SourceNode.create(id = SourceNodeId("orphan"), kind = "k")
        val clip = videoClip("c", setOf(SourceNodeId("direct")))
        val rows = decodeRows(
            runSourceBindingStatsQuery(project(listOf(direct, orphan), listOf(clip)), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(listOf("orphan"), row.orphanNodeIds, "only orphan id surfaces")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsRowsButTotalReflectsAllKinds() {
        val nodes = (1..5).map {
            SourceNode.create(id = SourceNodeId("n$it"), kind = "kind-$it")
        }
        val result = runSourceBindingStatsQuery(project(nodes), 2, 0)
        assertEquals(2, decodeRows(result.data).size)
        assertEquals(5, result.data.total)
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() {
        val nodes = (1..5).map {
            SourceNode.create(id = SourceNodeId("n${it.toString().padStart(2, '0')}"), kind = "k${it.toString().padStart(2, '0')}")
        }
        val result = runSourceBindingStatsQuery(project(nodes), 100, 2)
        val rows = decodeRows(result.data)
        // Sorted: k01..k05; offset=2 → start at k03.
        assertEquals(3, rows.size)
        assertEquals("k03", rows[0].kind)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun nonEmptyOutputShowsAggregatedTotalsAndWorstCoverageHint() {
        // Pin format: "<returned> of <total> kind(s) on project
        // <id>: <totalNodes> nodes total, <totalOrphans> orphan(s).
        // Lowest coverage: <kind> <covered>/<total> (<pct>%)."
        val charNode = SourceNode.create(id = SourceNodeId("c"), kind = "character_ref")
        val styleA = SourceNode.create(id = SourceNodeId("s1"), kind = "style_bible")
        val styleB = SourceNode.create(id = SourceNodeId("s2"), kind = "style_bible")
        val clip = videoClip("c", setOf(SourceNodeId("c")))
        val out = runSourceBindingStatsQuery(
            project(listOf(charNode, styleA, styleB), listOf(clip)),
            100,
            0,
        ).outputForLlm
        // Aggregate: 3 nodes total, 2 orphans (the 2 styles).
        assertTrue("3 nodes total" in out, "totalNodes; got: $out")
        assertTrue("2 orphan(s)" in out, "totalOrphans; got: $out")
        // Lowest coverage: style_bible (0/2 = 0%).
        assertTrue(
            "Lowest coverage: style_bible" in out,
            "worst-coverage hint; got: $out",
        )
        assertTrue("0/2" in out && "(0%)" in out, "worst details; got: $out")
    }

    @Test fun outputDropsWorstCoverageHintWhenNoKindsExist() {
        // Pin: empty case has no "Lowest coverage" tail (all
        // narrative drops to "no source nodes" marker).
        val out = runSourceBindingStatsQuery(project(emptyList()), 100, 0).outputForLlm
        assertTrue("Lowest coverage" !in out, "no worst tail when empty; got: $out")
    }

    @Test fun outputAggregatedTotalsSumAcrossKinds() {
        // Pin: "totalNodes" in summary = sum of totalNodes across
        // ALL kinds, not just first or last. Constructs 2 kinds
        // with 2 + 3 = 5 nodes total.
        val a1 = SourceNode.create(id = SourceNodeId("a1"), kind = "alpha")
        val a2 = SourceNode.create(id = SourceNodeId("a2"), kind = "alpha")
        val b1 = SourceNode.create(id = SourceNodeId("b1"), kind = "beta")
        val b2 = SourceNode.create(id = SourceNodeId("b2"), kind = "beta")
        val b3 = SourceNode.create(id = SourceNodeId("b3"), kind = "beta")
        val out = runSourceBindingStatsQuery(
            project(listOf(a1, a2, b1, b2, b3)),
            100,
            0,
        ).outputForLlm
        assertTrue("5 nodes total" in out, "got: $out")
    }

    @Test fun coveragePercentageFormattedAsTruncatedInteger() {
        // Pin: coverageRatio * 100 → toInt() truncates. 1/3 =
        // 0.333... * 100 = 33.33... → 33%.
        // 3 nodes, 1 bound.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val out = runSourceBindingStatsQuery(
            project(listOf(a, b, c), listOf(clip)),
            100,
            0,
        ).outputForLlm
        // Pin: 33% (truncated, NOT 33.3 / NOT 34 rounded).
        assertTrue("(33%)" in out, "truncated percentage; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runSourceBindingStatsQuery(project(emptyList()), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_SOURCE_BINDING_STATS, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val nodes = (1..5).map {
            SourceNode.create(id = SourceNodeId("n$it"), kind = "k-$it")
        }
        val result = runSourceBindingStatsQuery(project(nodes), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
