package io.talevia.core.domain.source.genre.narrative

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.genre.vlog.VlogEditIntentBody
import io.talevia.core.domain.source.genre.vlog.addVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.asVlogEditIntent
import io.talevia.core.domain.source.stale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization + DAG-propagation contract for the Narrative genre.
 *
 * Purpose of this suite is to prove VISION §5.1 "新 genre 要能通过扩展… 而不是改 Core"
 * by adding a second concrete genre (narrative) entirely outside Core and showing:
 *   - round-trip of each kind
 *   - typed readers return null on kind mismatch (kind-dispatch shape)
 *   - a narrative node + a vlog node coexist in the same Source without collision
 *   - a stale-propagation walk on a narrative world → scene → shot chain flows
 *     correctly through the genre-agnostic DAG lane.
 */
class NarrativeSourceTest {

    private val json = JsonConfig.default

    @Test fun worldNodeRoundTrip() {
        val body = NarrativeWorldBody(
            name = "neo-shibuya",
            description = "dense neon city, perpetual drizzle",
            era = "cyberpunk",
            referenceAssetIds = listOf(AssetId("art-1"), AssetId("art-2")),
        )
        val src = Source.EMPTY.addNarrativeWorld(SourceNodeId("world-1"), body)
        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))

        assertEquals(src, decoded)
        val node = decoded.byId.getValue(SourceNodeId("world-1"))
        assertEquals(NarrativeNodeKinds.WORLD, node.kind)
        assertEquals(body, node.asNarrativeWorld())
        assertNull(node.asNarrativeScene(), "kind mismatch must yield null")
    }

    @Test fun storylineNodeRoundTrip() {
        val body = NarrativeStorylineBody(
            logline = "A courier learns the package she's delivering is herself.",
            synopsis = "Mei discovers an origin…",
            acts = listOf("act 1 – the job", "act 2 – the chase", "act 3 – the choice"),
            targetDurationSeconds = 600,
        )
        val src = Source.EMPTY.addNarrativeStoryline(SourceNodeId("story-1"), body)
        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        assertEquals(body, decoded.byId.getValue(SourceNodeId("story-1")).asNarrativeStoryline())
    }

    @Test fun sceneAndShotNodesRoundTrip() {
        val scene = NarrativeSceneBody(
            title = "arrival at the checkpoint",
            location = "elevated border gate",
            timeOfDay = "dusk",
            action = "Mei rides up to the checkpoint on a mag-bike.",
            characterIds = listOf("mei", "guard-01"),
        )
        val shot = NarrativeShotBody(
            sceneId = "scene-1",
            framing = "medium",
            cameraMovement = "slow dolly-in",
            action = "Mei removes her helmet.",
            dialogue = "Let me pass.",
            speakerId = "mei",
            targetDurationSeconds = 4.0,
        )
        val src = Source.EMPTY
            .addNarrativeScene(SourceNodeId("scene-1"), scene)
            .addNarrativeShot(SourceNodeId("shot-1"), shot, parents = listOf(SourceRef(SourceNodeId("scene-1"))))

        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        assertEquals(scene, decoded.byId.getValue(SourceNodeId("scene-1")).asNarrativeScene())
        assertEquals(shot, decoded.byId.getValue(SourceNodeId("shot-1")).asNarrativeShot())

        val shotNode = decoded.byId.getValue(SourceNodeId("shot-1"))
        assertEquals(listOf(SourceRef(SourceNodeId("scene-1"))), shotNode.parents)
    }

    @Test fun narrativeAndVlogNodesCoexistInSameSource() {
        val src = Source.EMPTY
            .addNarrativeStoryline(
                SourceNodeId("story-a"),
                NarrativeStorylineBody(logline = "a test"),
            )
            .addVlogEditIntent(SourceNodeId("intent-b"), VlogEditIntentBody(description = "mixed-genre fixture"))

        // Typed accessors only decode their own kind; the other returns null.
        val storyNode = src.byId.getValue(SourceNodeId("story-a"))
        val vlogNode = src.byId.getValue(SourceNodeId("intent-b"))
        assertNotNull(storyNode.asNarrativeStoryline())
        assertNull(storyNode.asVlogEditIntent())
        assertNotNull(vlogNode.asVlogEditIntent())
        assertNull(vlogNode.asNarrativeStoryline())

        // Full project round-trips with the mixed graph intact.
        val project = Project(id = ProjectId("p-mix"), timeline = Timeline(), source = src)
        val decoded = json.decodeFromString(Project.serializer(), json.encodeToString(Project.serializer(), project))
        assertEquals(project, decoded)
    }

    @Test fun worldEditPropagatesStaleToDownstreamSceneAndShot() {
        val src = Source.EMPTY
            .addNarrativeWorld(
                SourceNodeId("world-1"),
                NarrativeWorldBody(name = "w", description = "dry"),
            )
            .addNarrativeScene(
                SourceNodeId("scene-1"),
                NarrativeSceneBody(title = "s1", action = "x"),
                parents = listOf(SourceRef(SourceNodeId("world-1"))),
            )
            .addNarrativeShot(
                SourceNodeId("shot-1"),
                NarrativeShotBody(sceneId = "scene-1"),
                parents = listOf(SourceRef(SourceNodeId("scene-1"))),
            )

        val stale = src.stale(setOf(SourceNodeId("world-1")))
        assertTrue(SourceNodeId("world-1") in stale)
        assertTrue(SourceNodeId("scene-1") in stale, "scene must be stale when world changes")
        assertTrue(SourceNodeId("shot-1") in stale, "shot must be stale when grandparent world changes")
    }
}
