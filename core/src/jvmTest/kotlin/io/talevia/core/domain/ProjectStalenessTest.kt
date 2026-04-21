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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Project-layer stale propagation: source-node changes flow through to the set of
 * clips that must be re-rendered (VISION §3.2).
 */
class ProjectStalenessTest {

    private fun clipBoundTo(id: String, vararg nodeIds: String, start: Long = 0): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun node(id: String, vararg parents: String) = SourceNode.create(
        id = SourceNodeId(id),
        kind = "test.node",
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    @Test fun clipBoundToChangedNodeIsStale() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(clipBoundTo("c1", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )

        val stale = project.staleClips(setOf(SourceNodeId("n1")))
        assertEquals(setOf(ClipId("c1")), stale)
    }

    @Test fun clipBoundToDescendantOfChangedNodeIsStale() {
        // n1 -> n2. Clip is bound to n2. Changing n1 should mark it stale transitively.
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(clipBoundTo("c1", "n2")),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1")),
        )

        val stale = project.staleClips(setOf(SourceNodeId("n1")))
        assertEquals(setOf(ClipId("c1")), stale)
    }

    @Test fun clipBoundToUnrelatedNodeIsFresh() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            clipBoundTo("c1", "n1"),
                            clipBoundTo("c2", "n2", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")).addNode(node("n2")),
        )

        assertEquals(setOf(ClipId("c1")), project.staleClips(setOf(SourceNodeId("n1"))))
        assertEquals(setOf(ClipId("c2")), project.freshClips(setOf(SourceNodeId("n1"))))
    }

    @Test fun clipWithEmptyBindingIsOutOfScopeForIncrementalTracking() {
        // Unbound clips (imported media, hand-authored text, legacy clips added
        // before the binding protocol) don't participate in incremental tracking
        // at all — they appear in NEITHER staleClips NOR freshClips. The third
        // "unknown" bucket is encoded as "absent from both sets". Prior behavior
        // that flagged them as always-stale polluted the signal (see
        // docs/decisions/2026-04-21-unbound-clip-stale-semantics.md).
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(clipBoundTo("c1" /* no nodes */)),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )

        val changed = setOf(SourceNodeId("n1"))
        assertTrue(project.staleClips(changed).isEmpty(), "unbound clip must not be reported stale")
        assertTrue(project.freshClips(changed).isEmpty(), "unbound clip must not be reported fresh either")
    }

    @Test fun mixedBoundAndUnboundOnlyReportsBoundStale() {
        // The practical regression case: a project with AIGC clips and imported
        // b-roll. Editing a character_ref should only mark the AIGC clips that
        // depend on it — the imported footage must NOT end up in the stale set
        // (there's no `baseInputs` to regenerate from, and the agent has no
        // meaningful action to take).
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            clipBoundTo("c-aigc-1", "n1"),
                            clipBoundTo("c-imported" /* no nodes */, start = 5),
                            clipBoundTo("c-aigc-2", "n1", start = 10),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )

        val changed = setOf(SourceNodeId("n1"))
        assertEquals(setOf(ClipId("c-aigc-1"), ClipId("c-aigc-2")), project.staleClips(changed))
        assertEquals(emptySet(), project.freshClips(changed))
        // c-imported appears in neither.
        val unionOfTracked = project.staleClips(changed) + project.freshClips(changed)
        assertTrue(ClipId("c-imported") !in unionOfTracked)
    }

    @Test fun unboundClipAbsentEvenWhenNoNodesChanged() {
        // Even when `changed` is empty, an unbound clip must not sneak into
        // freshClips either. The "not-tracked" bucket is absolute, not
        // contingent on whether any source edit happened.
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        id = TrackId("v"),
                        clips = listOf(
                            clipBoundTo("bound", "n1"),
                            clipBoundTo("unbound"),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )

        val changedNothing = emptySet<SourceNodeId>()
        assertTrue(project.staleClips(changedNothing).isEmpty())
        // freshClips only reports clips with a binding; empty changed-set means
        // no nodes are stale, so the bound clip is fresh; unbound still absent.
        assertEquals(setOf(ClipId("bound")), project.freshClips(changedNothing))
    }

    @Test fun emptyChangedSetProducesEmptyStaleSet() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(clipBoundTo("c1", "n1")))),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        assertEquals(emptySet(), project.staleClips(emptySet()))
    }
}
