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
 * Direct tests for [runOrphansQuery] — `source_query(select=
 * orphans)`, the "no clip uses this subtree" view. Cycle 112 audit:
 * 84 LOC, **zero** transitive test references. Symmetric counterpart
 * to [LeavesQuery] (cycle 111) — same row-and-summary shape, the
 * orphans-vs-leaves distinction is one of the kdoc's positional
 * axes (roots / leaves / orphans).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Orphan = `clipsBoundTo(id).isEmpty()`.** A node is orphan
 *    iff no clip binds to it directly OR transitively (via any
 *    DESCENDANT). The semantic is "no downstream clip needs this
 *    node". `chainWithClipOnRootMakesMidAndTipOrphans` constructs
 *    a→b→c with clip bound to a and verifies b and c are orphans
 *    (no clip downstream of them) but a is NOT (clip on a is
 *    downstream of a in the closure sense). A regression flipping
 *    the upstream/downstream walk would invert which nodes look
 *    "in use".
 *
 * 2. **`parentCount` + `childCount` distinguish stray nodes from
 *    subtree roots.** A standalone stray (parentCount=0,
 *    childCount=0) gets dropped wholesale; an orphan subtree root
 *    (childCount > 0) needs `prune-subtree` cleanup. The kdoc
 *    explicitly commits to this: "cleanup workflows can pick the
 *    right granularity". A regression dropping either field would
 *    force the LLM into follow-up queries to disambiguate.
 *
 * 3. **Sorted by id for diff stability.** Multiple orphans MUST
 *    appear in alphabetic order so re-running the query on the
 *    same DAG produces byte-identical output.
 */
class OrphansQueryTest {

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
            source = Source(nodes = nodes, revision = 13),
        )
    }

    private fun decodeRows(out: SourceQueryTool.Output): List<OrphanRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(OrphanRow.serializer()),
            out.rows,
        )

    // ── empty / trivial DAGs ──────────────────────────────────────

    @Test fun emptyDagReturnsZeroOrphansWithDedicatedMarker() {
        val result = runOrphansQuery(project(emptyList()))
        assertEquals(0, result.data.total)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin the empty case has its own marker — falling through
        // to the generic plural message "0 orphan source nodes" is
        // technically right but less useful than the explicit
        // "every node has a binding" hint.
        val out = result.outputForLlm
        assertTrue("No orphan source nodes" in out, "marker; got: $out")
        assertTrue("at least one clip binding" in out, "explanatory hint; got: $out")
    }

    @Test fun singleIsolatedNodeWithoutClipsIsOrphan() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runOrphansQuery(project(listOf(a))).data)
        val orphan = rows.single()
        assertEquals("a", orphan.id)
        assertEquals(0, orphan.parentCount, "no parents")
        assertEquals(0, orphan.childCount, "no children")
    }

    @Test fun singleNodeWithBoundClipIsNotOrphan() {
        // Pin: clip directly bound → not orphan. The empty list
        // should result.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runOrphansQuery(project(listOf(a), listOf(clip)))
        assertEquals(0, result.data.total, "node a has bound clip; not orphan")
        assertEquals(emptyList(), decodeRows(result.data))
    }

    // ── linear chain semantics: downstream walk ───────────────────

    @Test fun chainWithClipOnRootMakesMidAndTipOrphans() {
        // a → b → c. Clip binds to `a`. clipsBoundTo walks
        // downstream from each queried node:
        //   - a: closure {a, b, c}; clip's binding {a} intersects → not orphan
        //   - b: closure {b, c}; clip's binding {a} does NOT intersect → orphan
        //   - c: closure {c}; clip's binding {a} does NOT intersect → orphan
        // Mid + tip are orphans because no clip downstream of them
        // exists. This is the marquee semantic — a regression
        // flipping the downstream walk to upstream would make a
        // the orphan instead of b/c.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val rows = decodeRows(runOrphansQuery(project(listOf(a, b, c), listOf(clip))).data)
        val ids = rows.map { it.id }
        assertEquals(listOf("b", "c"), ids, "b and c are downstream-of-clip orphans; got: $ids")
    }

    @Test fun chainWithClipOnTipNoOrphan() {
        // a → b → c. Clip binds to `c`. Now:
        //   - a: closure {a, b, c}; clip {c} intersects → not orphan
        //   - b: closure {b, c}; clip {c} intersects → not orphan
        //   - c: closure {c}; clip {c} intersects → not orphan
        // Pin the inverse: a clip on the tip "uses" the whole
        // upstream chain.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("c")))
        val result = runOrphansQuery(project(listOf(a, b, c), listOf(clip)))
        assertEquals(0, result.data.total, "tip clip covers the chain; got: ${decodeRows(result.data)}")
    }

    // ── stray vs subtree-root distinction ─────────────────────────

    @Test fun fullyStrayNodeHasZeroParentsAndZeroChildren() {
        // Standalone orphan: parentCount=0, childCount=0. Per the
        // kdoc: "marks a fully stray node" — caller drops it
        // wholesale.
        val stray = SourceNode.create(id = SourceNodeId("stray"), kind = "k")
        val rows = decodeRows(runOrphansQuery(project(listOf(stray))).data)
        val o = rows.single()
        assertEquals(0, o.parentCount)
        assertEquals(0, o.childCount)
    }

    @Test fun orphanSubtreeRootHasChildCountGreaterThanZero() {
        // a → b → c. None have clips → all 3 orphans. Pin:
        //   - a: parentCount=0, childCount=1 (subtree root → prune
        //     wholesale)
        //   - b: parentCount=1, childCount=1 (mid)
        //   - c: parentCount=1, childCount=0 (tip)
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(runOrphansQuery(project(listOf(a, b, c))).data)
        val byId = rows.associateBy { it.id }
        assertEquals(0, byId.getValue("a").parentCount, "a has 0 parents")
        assertEquals(1, byId.getValue("a").childCount, "a has 1 child (b)")
        assertEquals(1, byId.getValue("b").parentCount, "b has 1 parent (a)")
        assertEquals(1, byId.getValue("b").childCount, "b has 1 child (c)")
        assertEquals(1, byId.getValue("c").parentCount, "c has 1 parent (b)")
        assertEquals(0, byId.getValue("c").childCount, "c has 0 children")
    }

    @Test fun diamondConvergenceChildCountReflectsAllParents() {
        // a → b, a → c, b → d, c → d. None have clips → all 4
        // orphans. Pin:
        //   - a: childCount=2 (b + c)
        //   - d: parentCount=2 (b + c)
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val rows = decodeRows(runOrphansQuery(project(listOf(a, b, c, d))).data)
        val byId = rows.associateBy { it.id }
        assertEquals(2, byId.getValue("a").childCount, "a has 2 children (b, c)")
        assertEquals(2, byId.getValue("d").parentCount, "d has 2 parents (b, c)")
    }

    // ── revision + kind round-trip ────────────────────────────────

    @Test fun rowCarriesRevisionAndKind() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "narrative.character",
            revision = 11,
        )
        val rows = decodeRows(runOrphansQuery(project(listOf(a))).data)
        val orphan = rows.single()
        assertEquals("narrative.character", orphan.kind)
        assertEquals(11L, orphan.revision)
    }

    // ── sorting ───────────────────────────────────────────────────

    @Test fun multipleOrphansSortedAlphabeticallyById() {
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k")
        val rows = decodeRows(runOrphansQuery(project(listOf(z, a, m))).data)
        assertEquals(listOf("a", "m", "z"), rows.map { it.id })
    }

    // ── partial-orphan graph (mixed bound + unbound) ──────────────

    @Test fun mixedGraphReportsOnlyOrphanSubset() {
        // a (clip-bound) + b (no clip). Pin: only b reported as
        // orphan; a is filtered out entirely (NOT included with
        // a "bound" flag).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runOrphansQuery(project(listOf(a, b), listOf(clip)))
        val rows = decodeRows(result.data)
        assertEquals(listOf("b"), rows.map { it.id }, "only b reported")
    }

    // ── title + outputForLlm pluralisation ────────────────────────

    @Test fun titlePluralisesOrphansCorrectly() {
        // 0 → "(0 orphans)", 1 → "(1 orphan)", 2 → "(2 orphans)".
        // Pin: predicate is `if (count == 1) ""`. Note "orphan"
        // appends "s" cleanly — no leafves trap.
        val zero = runOrphansQuery(project(emptyList()))
        assertTrue("(0 orphans)" in (zero.title ?: ""), "0 plural; got: ${zero.title}")
        val one = runOrphansQuery(
            project(listOf(SourceNode.create(id = SourceNodeId("a"), kind = "k"))),
        )
        assertTrue("(1 orphan)" in (one.title ?: ""), "1 singular; got: ${one.title}")
        val two = runOrphansQuery(
            project(
                listOf(
                    SourceNode.create(id = SourceNodeId("a"), kind = "k"),
                    SourceNode.create(id = SourceNodeId("b"), kind = "k"),
                ),
            ),
        )
        assertTrue("(2 orphans)" in (two.title ?: ""), "2 plural; got: ${two.title}")
    }

    @Test fun nonEmptyOutputIncludesStrayVsSubtreeGuidance() {
        // Pin the LLM-facing guidance: "parentCount=0 + childCount=0
        // marks fully stray; childCount > 0 marks subtree root".
        // Without these hints, the LLM would treat all orphans
        // identically.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runOrphansQuery(project(listOf(a))).outputForLlm
        assertTrue("parentCount=0" in out, "stray hint; got: $out")
        assertTrue("childCount=0" in out, "stray hint; got: $out")
        assertTrue("childCount > 0" in out, "subtree-root hint; got: $out")
        assertTrue("prune wholesale" in out, "subtree-root action; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runOrphansQuery(project(listOf(a)))
        assertEquals(SourceQueryTool.SELECT_ORPHANS, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(13, result.data.sourceRevision, "sourceRevision must round-trip")
    }
}
