package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Project.freshClips] —
 * `core/src/commonMain/kotlin/io/talevia/core/domain/
 * ProjectStalenessCommon.kt:190`. Cycle 299 audit: 1 prior
 * indirect ref (1 happy-path case in
 * [io.talevia.core.domain.ProjectStalenessTest] line 95);
 * `find -name 'FreshClips*Test*'` returns 0 files (cycle 289-
 * banked duplicate-check idiom).
 *
 * Same audit-pattern fallback as cycles 207-298.
 *
 * `freshClips` is the inverse of [Project.staleClips] over
 * the bound-clip subset: returns clips known to still be
 * valid after [changed] propagates. Together with `staleClips`
 * they form the 2-of-3 partition of the timeline:
 *
 *   bound + intersects-staleNodes → staleClips
 *   bound + does-NOT-intersect-staleNodes → freshClips
 *   unbound (sourceBinding empty) → NEITHER (3rd "unknown" bucket)
 *
 * Drift signals:
 *   - **Drift to include unbound clips in freshClips** silently
 *     re-pollutes the signal that was deliberately split in
 *     `docs/decisions/2026-04-21-unbound-clip-stale-semantics.md`.
 *   - **Drift to dedupe wrong** (fresh containing stale ids)
 *     would silently violate the partition invariant.
 *   - **Drift in track iteration** (skipping audio / text
 *     tracks) silently undercounts fresh clips.
 *   - **Drift in insertion-order preservation** (LinkedHashSet)
 *     would render UI listings in undefined order.
 */
class FreshClipsTest {

    private fun videoClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun audioClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Audio =
        Clip.Audio(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun textClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Text =
        Clip.Text(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 2.seconds),
            text = "hello",
            style = TextStyle(),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun node(id: String, vararg parents: String): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = "test.node",
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    private fun project(timeline: Timeline, source: Source = Source.EMPTY): Project = Project(
        id = ProjectId("p"),
        timeline = timeline,
        source = source,
    )

    // ── Empty / no-op cases ─────────────────────────────────

    @Test fun emptyProjectReturnsEmptyFreshSet() {
        // Pin: empty timeline → no clips to consider → empty
        // fresh set.
        val proj = project(Timeline())
        assertEquals(emptySet(), proj.freshClips(emptySet()))
    }

    @Test fun emptyChangedSetReturnsAllBoundClipsAsFresh() {
        // Pin: when nothing changed, every BOUND clip is
        // automatically fresh. Drift to "everything stale on
        // empty changed" or "ignore empty changed" surfaces
        // here.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", "n1"), videoClip("c2", "n2")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")).addNode(node("n2")),
        )
        val fresh = proj.freshClips(emptySet())
        assertEquals(
            setOf(ClipId("c1"), ClipId("c2")),
            fresh,
            "empty changed set MUST treat all bound clips as fresh",
        )
    }

    @Test fun emptyChangedSetSkipsUnboundClips() {
        // Pin: even with empty changed, unbound clips are
        // OUT of scope (3rd bucket), NOT fresh.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-bound", "n1"),
                            videoClip("c-unbound" /* no bindings */),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val fresh = proj.freshClips(emptySet())
        assertEquals(
            setOf(ClipId("c-bound")),
            fresh,
            "unbound clips MUST NOT appear in fresh set (3rd bucket invariant)",
        )
    }

    // ── Partition invariant: stale + fresh + unbound = all ─

    @Test fun partitionInvariantHoldsAcrossThreeBuckets() {
        // Marquee pin: staleClips ∩ freshClips = ∅; the
        // union with unbound clips covers all timeline clips.
        // Drift would let a bound clip end up in BOTH or
        // NEITHER set, violating the 3-bucket partition.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-stale", "n1", start = 0),
                            videoClip("c-fresh", "n2", start = 5),
                            videoClip("c-unbound" /* no bindings */, start = 10),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")).addNode(node("n2")),
        )
        val changed = setOf(SourceNodeId("n1"))
        val stale = proj.staleClips(changed)
        val fresh = proj.freshClips(changed)
        // No overlap.
        assertEquals(
            emptySet(),
            stale.intersect(fresh),
            "staleClips and freshClips MUST be disjoint",
        )
        // c-stale in stale, c-fresh in fresh, c-unbound in
        // neither.
        assertTrue(ClipId("c-stale") in stale)
        assertTrue(ClipId("c-fresh") in fresh)
        assertFalse(ClipId("c-unbound") in stale)
        assertFalse(ClipId("c-unbound") in fresh)
    }

    // ── Cascade through source DAG ─────────────────────────

    @Test fun freshExcludesClipsTransitivelyDownstreamOfChange() {
        // Marquee cascade pin: changing a parent node makes
        // every descendant-bound clip stale, NOT fresh. Drift
        // to consider only direct bindings would silently let
        // descendant-bound clips slip into fresh.
        // Source DAG: n1 → n2.
        // c1 binds n2 (descendant of n1).
        // c2 binds n3 (independent).
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-via-descendant", "n2", start = 0),
                            videoClip("c-independent", "n3", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1")) // n2 derives from n1
                .addNode(node("n3")),
        )
        val changed = setOf(SourceNodeId("n1"))
        val fresh = proj.freshClips(changed)
        assertFalse(
            ClipId("c-via-descendant") in fresh,
            "clip bound to a descendant of the changed node MUST NOT appear in fresh",
        )
        assertTrue(
            ClipId("c-independent") in fresh,
            "clip bound to an independent node MUST appear in fresh",
        )
    }

    // ── Multi-track coverage ───────────────────────────────

    @Test fun freshSpansAcrossVideoAudioTextTracks() {
        // Pin: freshClips iterates ALL tracks, not just
        // Video. Drift to consider only Track.Video would
        // silently undercount fresh clips on audio / text
        // tracks.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-v", "n1")),
                    ),
                    Track.Audio(
                        TrackId("a1"),
                        clips = listOf(audioClip("c-a", "n2")),
                    ),
                    Track.Subtitle(
                        TrackId("s1"),
                        clips = listOf(textClip("c-t", "n3")),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2"))
                .addNode(node("n3")),
        )
        // Change n1 only — c-v stale, c-a + c-t fresh.
        val fresh = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(
            setOf(ClipId("c-a"), ClipId("c-t")),
            fresh,
            "freshClips MUST span Video + Audio + Text tracks",
        )
    }

    @Test fun freshHandlesMultipleVideoTracks() {
        // Pin: 2 Track.Video → both walked. Drift to first-
        // track-only would silently miss clips on track 2.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1-v1", "n1")),
                    ),
                    Track.Video(
                        TrackId("v2"),
                        clips = listOf(videoClip("c1-v2", "n2")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")).addNode(node("n2")),
        )
        // Change n1 — c1-v1 stale, c1-v2 fresh.
        val fresh = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(
            setOf(ClipId("c1-v2")),
            fresh,
            "second video track's clips MUST appear in fresh",
        )
    }

    // ── Insertion-order preservation ───────────────────────

    @Test fun freshPreservesInsertionOrderAcrossTracks() {
        // Pin: per source line 192, LinkedHashSet preserves
        // insertion order. Drift to HashMap-based would make
        // UI listings non-deterministic.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", "n2", start = 0),
                            videoClip("c2", "n3", start = 5),
                            videoClip("c3", "n4", start = 10),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2"))
                .addNode(node("n3"))
                .addNode(node("n4")),
        )
        // Change n1 (no clip bound to it) → all 3 clips fresh
        // in declaration order.
        val fresh = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(
            listOf(ClipId("c1"), ClipId("c2"), ClipId("c3")),
            fresh.toList(),
            "fresh order MUST match declaration order across tracks",
        )
    }

    // ── Empty-binding clip exclusion ───────────────────────

    @Test fun unboundClipsNeverAppearInFreshEvenWhenChangedIsEmpty() {
        // Sister 3rd-bucket pin: even with empty changed
        // (where every BOUND clip is fresh), unbound clips
        // stay out.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-unbound1"),
                            videoClip("c-unbound2"),
                            videoClip("c-bound", "n1"),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val fresh = proj.freshClips(emptySet())
        assertEquals(
            setOf(ClipId("c-bound")),
            fresh,
            "unbound clips MUST NOT slip into fresh set even with empty changed",
        )
    }

    @Test fun freshIsEmptyWhenAllBoundClipsAreStale() {
        // Edge: every bound clip's binding intersects
        // changed → fresh is empty.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", "n1"),
                            videoClip("c2", "n1", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val fresh = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(
            emptySet(),
            fresh,
            "freshClips MUST be empty when every bound clip is stale",
        )
    }

    // ── Multi-binding clip handling ────────────────────────

    @Test fun multiBindingClipFreshOnlyIfNoBindingMatchesChanged() {
        // Pin: per staleClips contract `binding.any { in
        // staleNodes }`, a clip with multiple bindings is
        // stale if ANY of them intersects. So freshClips
        // requires ALL bindings to be untouched.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            // c1 binds n1 + n2 — partial change → stale.
                            videoClip("c1-multi-bind", "n1", "n2"),
                            // c2 binds n3 only — independent → fresh.
                            videoClip("c2-clean", "n3", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2"))
                .addNode(node("n3")),
        )
        val fresh = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(
            setOf(ClipId("c2-clean")),
            fresh,
            "multi-binding clip MUST be stale when ANY binding intersects changed (NOT fresh)",
        )
    }

    // ── Determinism + idempotency ──────────────────────────

    @Test fun freshIsDeterministicAcrossInvocations() {
        // Sanity pin: same inputs → same output (no random
        // / time-based content). Drift to non-deterministic
        // would surface here.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", "n2", start = 0),
                            videoClip("c2", "n3", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2"))
                .addNode(node("n3")),
        )
        val a = proj.freshClips(setOf(SourceNodeId("n1")))
        val b = proj.freshClips(setOf(SourceNodeId("n1")))
        val c = proj.freshClips(setOf(SourceNodeId("n1")))
        assertEquals(a, b)
        assertEquals(b, c)
    }
}
