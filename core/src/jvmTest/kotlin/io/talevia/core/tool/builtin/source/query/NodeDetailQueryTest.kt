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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runNodeDetailQuery] — `source_query(select=
 * node_detail)`, the single-node deep-zoom view. Cycle 115 audit:
 * 182 LOC, **zero** transitive test references; cycle 137 absorbed
 * the standalone `describe_source_node` tool into `source_query`
 * (per kdoc) but the behaviour-byte-identical guarantee was
 * unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Parent-missing fallback to "(missing)" string.** When a
 *    `SourceRef` points at a nodeId that no longer exists in the
 *    DAG (cycle 5's "dangling parent" hazard), the parentRef
 *    surfaces with `kind="(missing)"` rather than crashing or
 *    omitting the entry. The LLM needs to see the dangling ref
 *    to know the DAG is broken; silently dropping it would hide
 *    the structural fault.
 *
 * 2. **Direct vs transitive boundClips distinction.** The kdoc
 *    says: "every clip whose `sourceBinding` intersects this
 *    node's transitive-downstream closure (with `directly` flag)".
 *    A clip directly bound to node X has `directly=true`; a clip
 *    bound to a descendant of X has `directly=false`. The summary
 *    line uses these counts to render "$direct direct +
 *    $transitive transitive clip(s) bound" — wrong classification
 *    flips the user's "is this node load-bearing or
 *    opportunistically reachable?" intuition.
 *
 * 3. **`boundViaNodeIds` sorted for diff stability.** The set of
 *    descendants through which the binding flows must serialize
 *    in a stable order so re-running the query produces
 *    byte-identical output. A regression dropping the sort would
 *    shuffle the via-list nondeterministically.
 */
class NodeDetailQueryTest {

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
            source = Source(nodes = nodes, revision = 17),
        )
    }

    private fun input(nodeId: String?) = SourceQueryTool.Input(
        select = SourceQueryTool.SELECT_NODE_DETAIL,
        projectId = "p",
        id = nodeId,
    )

    private fun decodeRow(out: SourceQueryTool.Output): NodeDetailRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(NodeDetailRow.serializer()),
            out.rows,
        ).single()

    // ── input validation ──────────────────────────────────────────

    @Test fun missingIdErrorsLoudWithRecoveryHint() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runNodeDetailQuery(project(listOf(a)), input(null))
        }
        val msg = ex.message ?: ""
        assertTrue("requires id" in msg, "must mention required parameter; got: $msg")
        assertTrue("source_query(select=nodes)" in msg, "recovery hint; got: $msg")
    }

    @Test fun unknownIdErrorsLoudWithProjectIdAndRecoveryHint() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runNodeDetailQuery(project(listOf(a)), input("ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "must name the queried id; got: $msg")
        assertTrue("p" in msg, "must name project; got: $msg")
        assertTrue("source_query(select=nodes)" in msg, "recovery hint; got: $msg")
    }

    // ── basic shape ───────────────────────────────────────────────

    @Test fun isolatedNodeHasEmptyParentsChildrenBoundClips() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k", revision = 3)
        val result = runNodeDetailQuery(project(listOf(a)), input("a"))
        val row = decodeRow(result.data)
        assertEquals("a", row.nodeId)
        assertEquals("k", row.kind)
        assertEquals(3L, row.revision)
        assertEquals(emptyList(), row.parentRefs)
        assertEquals(emptyList(), row.children)
        assertEquals(emptyList(), row.boundClips)
        // Non-blank contentHash round-trips.
        assertTrue(row.contentHash.isNotBlank(), "contentHash populated")
    }

    // ── parent resolution ─────────────────────────────────────────

    @Test fun parentRefsCarryParentKindFromDag() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "narrative.shot",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a, b)), input("b")).data)
        val parent = row.parentRefs.single()
        assertEquals("a", parent.nodeId)
        assertEquals("narrative.scene", parent.kind, "parent's kind resolved from DAG")
    }

    @Test fun missingParentSurfacesWithMissingMarkerKind() {
        // Pin: dangling parent (id not in source.byId) → kind =
        // "(missing)". The LLM needs to see the dangling ref to
        // surface the structural fault; silently dropping it would
        // hide the DAG break.
        val orphan = SourceNode.create(
            id = SourceNodeId("orphan"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("ghost-parent"))),
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(orphan)), input("orphan")).data)
        val parent = row.parentRefs.single()
        assertEquals("ghost-parent", parent.nodeId)
        assertEquals("(missing)", parent.kind, "dangling parent gets marker kind")
    }

    @Test fun multipleParentsAreAllListed() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k1")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k2")
        val c = SourceNode.create(
            id = SourceNodeId("c"),
            kind = "k3",
            parents = listOf(SourceRef(SourceNodeId("a")), SourceRef(SourceNodeId("b"))),
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a, b, c)), input("c")).data)
        // Pin: parents listed in declaration order (NOT sorted —
        // the kdoc doesn't promise a sort, and parents semantically
        // come from the SourceNode itself which preserves order).
        assertEquals(listOf("a", "b"), row.parentRefs.map { it.nodeId })
        assertEquals(listOf("k1", "k2"), row.parentRefs.map { it.kind })
    }

    // ── children resolution ───────────────────────────────────────

    @Test fun childrenComeFromChildIndexSortedByNodeId() {
        // Pin: children come from `source.childIndex` and are
        // sorted by nodeId. Insertion order in the nodes list
        // doesn't determine the output order.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val row = decodeRow(runNodeDetailQuery(project(listOf(a, z, b, m)), input("a")).data)
        // Pin alphabetical: b, m, z (NOT z, b, m — insertion order).
        assertEquals(listOf("b", "m", "z"), row.children.map { it.nodeId })
    }

    @Test fun childrenCarryEachChildKind() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k1")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "narrative.scene",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a, b)), input("a")).data)
        assertEquals("narrative.scene", row.children.single().kind)
    }

    // ── boundClips: direct vs transitive ──────────────────────────

    @Test fun directlyBoundClipHasDirectlyTrue() {
        // Clip's sourceBinding contains the queried node directly
        // → directly=true.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val row = decodeRow(runNodeDetailQuery(project(listOf(a), listOf(clip)), input("a")).data)
        val bound = row.boundClips.single()
        assertEquals("c1", bound.clipId)
        assertEquals("v1", bound.trackId)
        assertEquals("asset-1", bound.assetId)
        assertEquals(true, bound.directly, "directly bound")
        // boundViaNodeIds includes the queried node itself.
        assertTrue(
            "a" in bound.boundViaNodeIds,
            "directly-bound list includes self; got: ${bound.boundViaNodeIds}",
        )
    }

    @Test fun transitivelyBoundClipHasDirectlyFalse() {
        // Clip binds to a descendant of `a` → bound to a
        // transitively, directly=false.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("b")))
        val row = decodeRow(runNodeDetailQuery(project(listOf(a, b), listOf(clip)), input("a")).data)
        val bound = row.boundClips.single()
        assertEquals(false, bound.directly, "transitive binding")
        // boundViaNodeIds points at `b` (the mediating descendant).
        assertEquals(listOf("b"), bound.boundViaNodeIds)
    }

    @Test fun boundViaNodeIdsAreSortedAlphabetically() {
        // Pin diff stability: a clip whose binding spans multiple
        // descendants of the queried node MUST surface
        // boundViaNodeIds in sorted order. Without the sort,
        // re-running the query nondeterministically shuffles the
        // via-list, breaking byte-identical output expectations.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val zChild = SourceNode.create(id = SourceNodeId("z"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val bChild = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val mChild = SourceNode.create(id = SourceNodeId("m"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        // Clip binds to all 3 descendants (insertion-order z, b, m).
        val clip = videoClip(
            "c1",
            "asset-1",
            setOf(SourceNodeId("z"), SourceNodeId("b"), SourceNodeId("m")),
        )
        val row = decodeRow(
            runNodeDetailQuery(project(listOf(a, zChild, bChild, mChild), listOf(clip)), input("a")).data,
        )
        val bound = row.boundClips.single()
        // Pin alphabetical: b, m, z (NOT z, b, m).
        assertEquals(listOf("b", "m", "z"), bound.boundViaNodeIds)
    }

    @Test fun mixedDirectAndTransitiveClipsAreAllReported() {
        // a → b. Clip-1 directly binds to a, Clip-2 transitively
        // binds via b. Both surface in boundClips with correct
        // directly flags.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val direct = videoClip("c-direct", "ad1", setOf(SourceNodeId("a")))
        val trans = videoClip("c-trans", "at1", setOf(SourceNodeId("b")))
        val row = decodeRow(
            runNodeDetailQuery(project(listOf(a, b), listOf(direct, trans)), input("a")).data,
        )
        assertEquals(2, row.boundClips.size)
        val byClip = row.boundClips.associateBy { it.clipId }
        assertEquals(true, byClip.getValue("c-direct").directly)
        assertEquals(false, byClip.getValue("c-trans").directly)
    }

    // ── outputForLlm summary line ─────────────────────────────────

    @Test fun outputForLlmSummaryShowsKindIdRevisionAndShortHash() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene", revision = 7)
        val out = runNodeDetailQuery(project(listOf(a)), input("a")).outputForLlm
        // Pin format: "<kind> <id> (rev=<rev>, hash=<8chars>): <summary>"
        assertTrue("narrative.scene a" in out, "kind+id; got: $out")
        assertTrue("rev=7" in out, "revision; got: $out")
        assertTrue("hash=" in out, "hash field; got: $out")
        // The hash prefix: take(8) keeps it short. Pin that the
        // truncated form has 8 chars.
        val hashPart = out.substringAfter("hash=").substringBefore(")")
        assertEquals(8, hashPart.length, "hash must be 8 chars; got: '$hashPart'")
    }

    @Test fun outputForLlmParentsLineEmptyShowsNoneMarker() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runNodeDetailQuery(project(listOf(a)), input("a")).outputForLlm
        assertTrue("- parents: none" in out, "no-parents marker; got: $out")
    }

    @Test fun outputForLlmParentsLineListsParentIdAndKind() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "narrative.shot",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val out = runNodeDetailQuery(project(listOf(a, b)), input("b")).outputForLlm
        // Pin format: "- parents: a(narrative.scene)".
        assertTrue("- parents: a(narrative.scene)" in out, "parent format; got: $out")
    }

    @Test fun outputForLlmChildrenLineFormatsSimilarly() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "narrative.shot",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val out = runNodeDetailQuery(project(listOf(a, b)), input("a")).outputForLlm
        assertTrue("- children: b(narrative.shot)" in out, "child format; got: $out")
    }

    @Test fun outputForLlmBoundLineNoClipsBound() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runNodeDetailQuery(project(listOf(a)), input("a")).outputForLlm
        assertTrue("- no clips bound" in out, "no-clips marker; got: $out")
    }

    @Test fun outputForLlmBoundLineDirectOnly() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val out = runNodeDetailQuery(project(listOf(a), listOf(clip)), input("a")).outputForLlm
        // Pin: "- 1 clip(s) bound directly" (no transitive).
        assertTrue(
            "- 1 clip(s) bound directly" in out,
            "direct-only line; got: $out",
        )
        assertTrue("transitive" !in out, "no transitive mention when 0; got: $out")
    }

    @Test fun outputForLlmBoundLineMixedDirectAndTransitive() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val direct = videoClip("c-direct", "ad", setOf(SourceNodeId("a")))
        val trans = videoClip("c-trans", "at", setOf(SourceNodeId("b")))
        val out = runNodeDetailQuery(
            project(listOf(a, b), listOf(direct, trans)),
            input("a"),
        ).outputForLlm
        // Pin: "- 1 direct + 1 transitive clip(s) bound".
        assertTrue(
            "- 1 direct + 1 transitive clip(s) bound" in out,
            "mixed line; got: $out",
        )
    }

    // ── humanSummary fallback (opaque body) ───────────────────────

    @Test fun summaryForOpaqueBodyListsKeysAndFirstString() {
        // Pin the opaque-body fallback in `humanSummary`. A node
        // with a generic kind + JsonObject body lists "keys=" and
        // the first string-valued field's first 60 chars.
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "narrative.scene",
            body = buildJsonObject {
                put("description", "the quick brown fox")
                put("priority", 5)
            },
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a)), input("a")).data)
        val summary = row.summary
        assertTrue("keys=" in summary, "keys prefix; got: $summary")
        assertTrue("description" in summary, "key listed; got: $summary")
        assertTrue("the quick brown fox" in summary, "first-string surfaces; got: $summary")
    }

    @Test fun summaryForOpaqueBodyWithoutStringValueShowsKeysOnly() {
        // Pin: when no string field exists, summary stops at the
        // keys list — no orphan "; " trail.
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("priority", 5) },
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a)), input("a")).data)
        val summary = row.summary
        assertTrue("keys={priority}" in summary, "keys-only form; got: $summary")
        // No trailing semicolon-space.
        assertTrue(summary.endsWith("}") || !summary.endsWith("; "), "no trailing artefact; got: $summary")
    }

    @Test fun summaryForNonObjectBodyFallsBackToOpaqueMarker() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = JsonPrimitive("just a string body"),
        )
        val row = decodeRow(runNodeDetailQuery(project(listOf(a)), input("a")).data)
        assertEquals("(opaque body)", row.summary)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runNodeDetailQuery(project(listOf(a)), input("a"))
        assertEquals(SourceQueryTool.SELECT_NODE_DETAIL, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(17, result.data.sourceRevision, "sourceRevision must round-trip")
    }

    @Test fun titleIncludesKindAndId() {
        val a = SourceNode.create(id = SourceNodeId("alpha"), kind = "narrative.scene")
        val result = runNodeDetailQuery(project(listOf(a)), input("alpha"))
        assertTrue(
            "source_query node_detail narrative.scene alpha" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
