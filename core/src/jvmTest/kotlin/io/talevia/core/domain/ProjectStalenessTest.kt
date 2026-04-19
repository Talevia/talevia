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

    @Test fun clipWithEmptyBindingIsAlwaysStale() {
        // Unbound clips can't prove they're fresh. That's the whole contract — opt out
        // of binding = opt out of incremental compilation.
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

        val stale = project.staleClips(setOf(SourceNodeId("n1")))
        assertEquals(setOf(ClipId("c1")), stale)
        assertTrue(project.freshClips(setOf(SourceNodeId("n1"))).isEmpty())
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
