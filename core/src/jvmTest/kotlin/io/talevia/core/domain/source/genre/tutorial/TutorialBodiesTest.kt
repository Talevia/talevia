package io.talevia.core.domain.source.genre.tutorial

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trip + DAG-propagation hook contract for the Tutorial genre. Same
 * three-property shape as [io.talevia.core.domain.source.genre.musicmv.MusicMvBodiesTest]:
 * round-trip, kind-mismatch yields null, distinct bodies yield distinct
 * `contentHash`.
 */
class TutorialBodiesTest {

    @Test fun scriptRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = TutorialScriptBody(
            title = "Talevia in 90 seconds",
            spokenText = "Today we'll build a vlog end-to-end…",
            segments = listOf("intro", "install", "first run", "wrap"),
            targetDurationSeconds = 90,
        )
        val src = Source.EMPTY.addTutorialScript(SourceNodeId("script-1"), body)
        val node = src.byId.getValue(SourceNodeId("script-1"))

        assertEquals(TutorialNodeKinds.SCRIPT, node.kind)
        assertEquals(body, node.asTutorialScript())
        assertNull(node.asTutorialBrollLibrary(), "kind mismatch must yield null")
        assertNull(node.asTutorialBrandSpec(), "kind mismatch must yield null")
    }

    @Test fun brollLibraryRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = TutorialBrollLibraryBody(
            assetIds = listOf(AssetId("screen-1"), AssetId("screen-2")),
            notes = "re-record install step once 1.0 ships",
        )
        val src = Source.EMPTY.addTutorialBrollLibrary(SourceNodeId("broll-1"), body)
        val node = src.byId.getValue(SourceNodeId("broll-1"))

        assertEquals(TutorialNodeKinds.BROLL_LIBRARY, node.kind)
        assertEquals(body, node.asTutorialBrollLibrary())
        assertNull(node.asTutorialScript())
        assertNull(node.asTutorialBrandSpec())
    }

    @Test fun brandSpecRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = TutorialBrandSpecBody(
            productName = "Talevia CLI",
            brandColors = listOf("#0A0A0A", "#F5F5F5"),
            lowerThirdStyle = "minimal white text, bottom-left, 24pt",
            logoAssetId = AssetId("logo-1"),
        )
        val src = Source.EMPTY.addTutorialBrandSpec(SourceNodeId("brand-1"), body)
        val node = src.byId.getValue(SourceNodeId("brand-1"))

        assertEquals(TutorialNodeKinds.BRAND_SPEC, node.kind)
        assertEquals(body, node.asTutorialBrandSpec())
        assertNull(node.asTutorialScript())
        assertNull(node.asTutorialBrollLibrary())
    }

    @Test fun distinctBodiesHaveDistinctContentHashes() {
        val a = Source.EMPTY.addTutorialScript(
            SourceNodeId("s"),
            TutorialScriptBody(title = "A", spokenText = "first"),
        )
        val b = Source.EMPTY.addTutorialScript(
            SourceNodeId("s"),
            TutorialScriptBody(title = "B", spokenText = "second"),
        )
        val aHash = a.byId.getValue(SourceNodeId("s")).contentHash
        val bHash = b.byId.getValue(SourceNodeId("s")).contentHash
        assertNotNull(aHash)
        assertNotNull(bHash)
        assertNotEquals(aHash, bHash, "distinct bodies must yield distinct contentHash")
    }
}
