package io.talevia.core.domain.source.genre.musicmv

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trip + DAG-propagation hook contract for the Music-MV genre.
 *
 * Asserts the three properties every genre body must uphold so VISION §2's
 * "new genre = new source schema, no Core change" claim stays honest:
 *   1. Each body round-trips through `addXxx` + `asXxx()`.
 *   2. `asXxx()` returns `null` on kind-mismatched nodes (kind-dispatch shape).
 *   3. Distinct bodies produce distinct `contentHash`es — otherwise the DAG
 *      stale-propagation lane cannot spot edits that only touch body fields.
 */
class MusicMvBodiesTest {

    @Test fun trackRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = MusicMvTrackBody(
            assetId = AssetId("song-1"),
            title = "Neon Tides",
            artist = "Aria",
            bpm = 128,
            keySignature = "C# minor",
            lyricsRef = "lyrics-node-1",
        )
        val src = Source.EMPTY.addMusicMvTrack(SourceNodeId("track-1"), body)
        val node = src.byId.getValue(SourceNodeId("track-1"))

        assertEquals(MusicMvNodeKinds.TRACK, node.kind)
        assertEquals(body, node.asMusicMvTrack())
        assertNull(node.asMusicMvVisualConcept(), "kind mismatch must yield null")
        assertNull(node.asMusicMvPerformanceShot(), "kind mismatch must yield null")
    }

    @Test fun visualConceptRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = MusicMvVisualConceptBody(
            logline = "A neon rain drenches a one-take dance sequence.",
            mood = "melancholic euphoria",
            motifs = listOf("neon rain", "one-take choreography", "mirror reflections"),
            paletteRef = "palette-1",
        )
        val src = Source.EMPTY.addMusicMvVisualConcept(SourceNodeId("concept-1"), body)
        val node = src.byId.getValue(SourceNodeId("concept-1"))

        assertEquals(MusicMvNodeKinds.VISUAL_CONCEPT, node.kind)
        assertEquals(body, node.asMusicMvVisualConcept())
        assertNull(node.asMusicMvTrack())
        assertNull(node.asMusicMvPerformanceShot())
    }

    @Test fun performanceShotRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = MusicMvPerformanceShotBody(
            performer = "Aria",
            action = "lip-sync to chorus",
            assetIds = listOf(AssetId("take-1"), AssetId("take-2")),
            targetDurationSeconds = 12.5,
        )
        val src = Source.EMPTY.addMusicMvPerformanceShot(SourceNodeId("shot-1"), body)
        val node = src.byId.getValue(SourceNodeId("shot-1"))

        assertEquals(MusicMvNodeKinds.PERFORMANCE_SHOT, node.kind)
        assertEquals(body, node.asMusicMvPerformanceShot())
        assertNull(node.asMusicMvTrack())
        assertNull(node.asMusicMvVisualConcept())
    }

    @Test fun distinctBodiesHaveDistinctContentHashes() {
        val a = Source.EMPTY.addMusicMvTrack(
            SourceNodeId("t"),
            MusicMvTrackBody(assetId = AssetId("song-a"), title = "A"),
        )
        val b = Source.EMPTY.addMusicMvTrack(
            SourceNodeId("t"),
            MusicMvTrackBody(assetId = AssetId("song-b"), title = "B"),
        )
        val aHash = a.byId.getValue(SourceNodeId("t")).contentHash
        val bHash = b.byId.getValue(SourceNodeId("t")).contentHash
        assertNotNull(aHash)
        assertNotNull(bHash)
        assertNotEquals(aHash, bHash, "distinct bodies must yield distinct contentHash")
    }
}
