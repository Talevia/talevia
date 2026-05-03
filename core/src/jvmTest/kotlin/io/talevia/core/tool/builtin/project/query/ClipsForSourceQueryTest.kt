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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runClipsForSourceQuery] —
 * `project_query(select=clips_for_source)`. The "which clips depend
 * on this source node?" lookup that drives blast-radius preview
 * before a source edit. Cycle 121 audit: 71 LOC, **zero**
 * transitive test references. Symmetric counterpart to
 * `ClipsForAssetQuery` (cycle 118) — same shape, different binding
 * dimension.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Unknown source node id throws (NOT empty result).** Same
 *    typo-throws-not-empty contract as cycle 118's ClipsForAsset.
 *    A regression returning empty would let the LLM mistakenly
 *    conclude "this node has no consumers, safe to delete" when
 *    really the typo is the issue. Pinned with the
 *    `source_query(select=nodes)` recovery hint.
 *
 * 2. **`directlyBound=true` only when the clip's `sourceBinding`
 *    contains the queried node directly.** Transitive-via-
 *    descendant bindings get `directlyBound=false` plus
 *    `boundVia` listing the mediating descendants. The
 *    distinction drives the LLM's "is this clip directly
 *    affected by editing this node?" intuition — wrong
 *    classification flips that signal.
 *
 * 3. **`boundVia` lists the descendant ids through which the
 *    binding flows.** A clip bound to multiple descendants
 *    surfaces all of them in the boundVia list, NOT just the
 *    first match. Important for the LLM's "which descendant
 *    drove this clip's stale-marking?" introspection.
 */
class ClipsForSourceQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId("asset-$id"),
        sourceBinding = binding,
    )

    private fun textClip(id: String, binding: Set<SourceNodeId>) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = "subtitle",
        sourceBinding = binding,
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes, revision = 5),
        )
    }

    private fun input(sourceNodeId: String?) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_CLIPS_FOR_SOURCE,
        sourceNodeId = sourceNodeId,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<ClipForSourceRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ClipForSourceRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingSourceNodeIdErrorsLoud() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runClipsForSourceQuery(project(listOf(a)), input(null), 100, 0)
        }
        assertTrue(
            "requires the 'sourceNodeId'" in (ex.message ?: ""),
            "got: ${ex.message}",
        )
    }

    @Test fun unknownSourceNodeIdThrowsWithRecoveryHint() {
        // The marquee pin. Same shape as cycle 118's typo-throws
        // semantic on ClipsForAssetQuery — typos surface as
        // errors, not silent "no consumers, safe to delete"
        // misjudgment. Recovery hint points at
        // source_query(select=nodes).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runClipsForSourceQuery(project(listOf(a)), input("ghost"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "must name id; got: $msg")
        assertTrue("not found" in msg, "got: $msg")
        assertTrue("source_query(select=nodes)" in msg, "recovery hint; got: $msg")
    }

    // ── basic shape: empty / no matches ───────────────────────────

    @Test fun knownNodeWithNoBoundClipsReportsEmpty() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runClipsForSourceQuery(project(listOf(a)), input("a"), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        // Pin: empty marker mentions both directly AND
        // transitively (the LLM needs to know the query covers
        // both modes).
        assertTrue(
            "directly or transitively" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    // ── directly-bound vs transitive-bound clips ──────────────────

    @Test fun directlyBoundClipHasDirectlyTrueAndBoundViaIncludesSelf() {
        // Clip's sourceBinding contains the queried node directly.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val rows = decodeRows(
            runClipsForSourceQuery(project(listOf(a), listOf(clip)), input("a"), 100, 0).data,
        )
        val row = rows.single()
        assertEquals("c1", row.clipId)
        assertEquals("vt", row.trackId)
        assertEquals("asset-c1", row.assetId)
        assertEquals(true, row.directlyBound)
        // Pin: boundVia includes the queried node itself when
        // directly bound (the closure includes the root).
        assertTrue("a" in row.boundVia, "directly-bound list includes self; got: ${row.boundVia}")
    }

    @Test fun transitivelyBoundClipHasDirectlyFalse() {
        // a → b. Clip binds to b. Querying for `a`: a's downstream
        // closure is {a, b}; clip's binding {b} intersects via b
        // → directlyBound=false, boundVia=[b].
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val clip = videoClip("c1", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runClipsForSourceQuery(project(listOf(a, b), listOf(clip)), input("a"), 100, 0).data,
        )
        val row = rows.single()
        assertEquals(false, row.directlyBound, "transitive binding")
        // boundVia points at `b` (the mediating descendant).
        assertEquals(listOf("b"), row.boundVia)
    }

    @Test fun mixedDirectAndTransitiveClipsBothSurface() {
        // a → b. clip-direct binds to a; clip-trans binds to b.
        // Both report against query for `a`, with correct
        // directly flags.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val direct = videoClip("c-direct", setOf(SourceNodeId("a")))
        val trans = videoClip("c-trans", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runClipsForSourceQuery(
                project(listOf(a, b), listOf(direct, trans)),
                input("a"),
                100,
                0,
            ).data,
        )
        val byClip = rows.associateBy { it.clipId }
        assertEquals(true, byClip.getValue("c-direct").directlyBound)
        assertEquals(false, byClip.getValue("c-trans").directlyBound)
    }

    @Test fun textClipBoundCarriesNullAssetId() {
        // Text clips have no assetId in the domain model
        // (`Clip.Text` doesn't carry one). Pin: assetId field
        // surfaces null, NOT empty string or omitted.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val text = textClip("t1", setOf(SourceNodeId("a")))
        // Use a Subtitle track for the text clip (Track.Video
        // doesn't accept a Text clip in production sense).
        val tracks = listOf(Track.Subtitle(TrackId("st"), listOf(text)))
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = listOf(a), revision = 5),
        )
        val rows = decodeRows(
            runClipsForSourceQuery(proj, input("a"), 100, 0).data,
        )
        val row = rows.single()
        assertEquals("t1", row.clipId)
        assertEquals("st", row.trackId)
        // assetId is null per kdoc on ClipForSourceRow.
        assertEquals(null, row.assetId)
    }

    // ── boundVia: multi-descendant case ───────────────────────────

    @Test fun clipBoundToMultipleDescendantsListsAllInBoundVia() {
        // a → b, a → c, a → d. Clip binds to {b, d}. Query for
        // `a`: closure is {a, b, c, d}; clip's binding intersects
        // via {b, d}. boundVia must list both — NOT just the
        // first match.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(id = SourceNodeId("d"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val clip = videoClip("c1", setOf(SourceNodeId("b"), SourceNodeId("d")))
        val rows = decodeRows(
            runClipsForSourceQuery(
                project(listOf(a, b, c, d), listOf(clip)),
                input("a"),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        // Pin: both b and d appear; c does NOT (clip didn't bind c).
        assertTrue("b" in row.boundVia, "b must surface; got: ${row.boundVia}")
        assertTrue("d" in row.boundVia, "d must surface; got: ${row.boundVia}")
        assertTrue("c" !in row.boundVia, "c did not bind; got: ${row.boundVia}")
    }

    // ── unrelated clips not surfaced ──────────────────────────────

    @Test fun clipsBoundToOtherSourceNodesAreNotMatched() {
        // a + b unrelated. clip binds to b. Query for `a` returns
        // empty (b is NOT downstream of a, no closure overlap).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("b")))
        val rows = decodeRows(
            runClipsForSourceQuery(project(listOf(a, b), listOf(clip)), input("a"), 100, 0).data,
        )
        assertEquals(0, rows.size, "clip on b doesn't match query for a")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsResultButTotalReflectsAll() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..5).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val result = runClipsForSourceQuery(project(listOf(a), clips), input("a"), 2, 0)
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total, "total = all matches")
    }

    @Test fun offsetSkipsFirstNRows() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..5).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val result = runClipsForSourceQuery(project(listOf(a), clips), input("a"), 100, 2)
        val rows = decodeRows(result.data)
        // Implementation order matches clipsBoundTo's track-walk.
        // Pin: 3 rows survive after skipping 2.
        assertEquals(3, rows.size)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun outputForLlmListsHeadFiveClipsWithDirectFlagDistinction() {
        // Pin format: directly-bound clips → just clipId; transitive
        // → "<clipId> (via <descendants>)".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val direct = videoClip("c-direct", setOf(SourceNodeId("a")))
        val trans = videoClip("c-trans", setOf(SourceNodeId("b")))
        val out = runClipsForSourceQuery(
            project(listOf(a, b), listOf(direct, trans)),
            input("a"),
            100,
            0,
        ).outputForLlm
        // Pin: count + node id surfaced.
        assertTrue("2 clip(s) bind source node a" in out, "count + node id; got: $out")
        // Pin: direct clip appears WITHOUT "via" suffix.
        assertTrue("c-direct" in out, "direct clip surfaces; got: $out")
        // Pin: transitive clip appears WITH "(via b)" suffix.
        assertTrue("c-trans (via b)" in out, "via suffix; got: $out")
    }

    @Test fun outputForLlmShowsEllipsisWhenMoreThanFive() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..7).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val out = runClipsForSourceQuery(project(listOf(a), clips), input("a"), 100, 0).outputForLlm
        assertTrue(out.endsWith("; …"), "ellipsis when > 5; got: $out")
    }

    @Test fun outputForLlmNoEllipsisWhenAtMostFive() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..3).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val out = runClipsForSourceQuery(project(listOf(a), clips), input("a"), 100, 0).outputForLlm
        assertTrue("…" !in out, "no ellipsis when ≤ 5; got: $out")
    }

    @Test fun outputForLlmEmptyMatchHasNoConsumersHint() {
        // Pin: empty result summary explicitly mentions "directly
        // or transitively" so the LLM knows the query covers both
        // and can decide whether the node is unused.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runClipsForSourceQuery(project(listOf(a)), input("a"), 100, 0).outputForLlm
        assertTrue(
            "No clips bind source node a (directly or transitively)" in out,
            "empty marker; got: $out",
        )
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runClipsForSourceQuery(project(listOf(a)), input("a"), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_CLIPS_FOR_SOURCE, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..5).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val result = runClipsForSourceQuery(project(listOf(a), clips), input("a"), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
