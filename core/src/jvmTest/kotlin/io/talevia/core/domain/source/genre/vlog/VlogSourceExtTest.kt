package io.talevia.core.domain.source.genre.vlog

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the Vlog genre's typed builders +
 * accessors in
 * `core/domain/source/genre/vlog/VlogSourceExt.kt`. Three
 * paired writer/reader extensions wrap the kind-tagged
 * `SourceNode.body` JsonElement encode/decode round-trip.
 * Cycle 149 audit: 77 LOC.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Each writer stamps the genre-namespaced kind
 *    constant.** `addVlogRawFootage` → `vlog.raw_footage`,
 *    etc. Drift to a free-form / un-prefixed kind would
 *    collide with other genres' nodes (every genre uses the
 *    `<genre>.<kind>` convention for namespacing).
 *
 * 2. **Each reader returns null on kind mismatch — never
 *    throws.** Per kdoc: "decode only if [SourceNode.kind]
 *    matches the expected constant; otherwise the accessor
 *    returns `null` rather than throwing. That shape means
 *    callers can do kind-dispatch with a `when`/`let` chain
 *    without try/catch." Drift to throwing would force
 *    every call site into try/catch, defeating the design.
 *
 * 3. **Round-trip preserves body content bit-exact.** Every
 *    writer/reader pair must satisfy `read(write(body)) ==
 *    body` for any well-formed body — the canonical
 *    `JsonConfig.default` instance roundtrips cleanly when
 *    used on both sides.
 */
class VlogSourceExtTest {

    // ── kind constants (sanity) ──────────────────────────────────

    @Test fun kindConstantsUseGenreNamespacedConvention() {
        // Pin: every constant starts with "vlog." — this is
        // the genre-namespacing convention. A drift to
        // `"raw_footage"` (un-prefixed) would collide with
        // any other genre that defines "raw_footage" (e.g.
        // a hypothetical narrative.raw_footage).
        assertTrue(VlogNodeKinds.RAW_FOOTAGE.startsWith("vlog."), "got: ${VlogNodeKinds.RAW_FOOTAGE}")
        assertTrue(VlogNodeKinds.EDIT_INTENT.startsWith("vlog."), "got: ${VlogNodeKinds.EDIT_INTENT}")
        assertTrue(VlogNodeKinds.STYLE_PRESET.startsWith("vlog."), "got: ${VlogNodeKinds.STYLE_PRESET}")
    }

    // ── writers stamp correct kind ───────────────────────────────

    @Test fun addVlogRawFootageStampsRawFootageKind() {
        val source = Source.EMPTY.addVlogRawFootage(
            id = SourceNodeId("rf-1"),
            body = VlogRawFootageBody(
                assetIds = listOf(AssetId("a1"), AssetId("a2")),
                notes = "B-roll from morning shoot",
            ),
        )
        val node = source.byId.getValue(SourceNodeId("rf-1"))
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, node.kind)
    }

    @Test fun addVlogEditIntentStampsEditIntentKind() {
        val source = Source.EMPTY.addVlogEditIntent(
            id = SourceNodeId("ei-1"),
            body = VlogEditIntentBody(description = "graduation day"),
        )
        val node = source.byId.getValue(SourceNodeId("ei-1"))
        assertEquals(VlogNodeKinds.EDIT_INTENT, node.kind)
    }

    @Test fun addVlogStylePresetStampsStylePresetKind() {
        val source = Source.EMPTY.addVlogStylePreset(
            id = SourceNodeId("sp-1"),
            body = VlogStylePresetBody(name = "warm cinema"),
        )
        val node = source.byId.getValue(SourceNodeId("sp-1"))
        assertEquals(VlogNodeKinds.STYLE_PRESET, node.kind)
    }

    // ── readers return null on kind mismatch ─────────────────────

    @Test fun rawFootageReaderReturnsNullForEditIntentNode() {
        // Marquee no-throw pin: a node of a different vlog
        // kind passed to the wrong reader returns null, NOT
        // throws. Lets callers chain `when`/`let` without
        // try/catch.
        val source = Source.EMPTY.addVlogEditIntent(
            id = SourceNodeId("ei-1"),
            body = VlogEditIntentBody(description = "x"),
        )
        val node = source.byId.getValue(SourceNodeId("ei-1"))
        assertNull(node.asVlogRawFootage(), "wrong-kind read returns null")
    }

    @Test fun editIntentReaderReturnsNullForRawFootageNode() {
        val source = Source.EMPTY.addVlogRawFootage(
            id = SourceNodeId("rf-1"),
            body = VlogRawFootageBody(assetIds = listOf(AssetId("a"))),
        )
        val node = source.byId.getValue(SourceNodeId("rf-1"))
        assertNull(node.asVlogEditIntent())
    }

    @Test fun stylePresetReaderReturnsNullForRawFootageNode() {
        val source = Source.EMPTY.addVlogRawFootage(
            id = SourceNodeId("rf-1"),
            body = VlogRawFootageBody(assetIds = listOf(AssetId("a"))),
        )
        val node = source.byId.getValue(SourceNodeId("rf-1"))
        assertNull(node.asVlogStylePreset())
    }

    @Test fun readersReturnNullForCrossGenreKind() {
        // Pin: a node with a non-vlog kind (e.g. consistency
        // genre's "core.consistency.character_ref") read by a
        // vlog accessor returns null. The kind-mismatch check
        // is the same regardless of which genre the kind
        // belongs to.
        val foreignNode = SourceNode(
            id = SourceNodeId("foreign"),
            kind = "core.consistency.character_ref",
        )
        assertNull(foreignNode.asVlogRawFootage())
        assertNull(foreignNode.asVlogEditIntent())
        assertNull(foreignNode.asVlogStylePreset())
    }

    @Test fun readersReturnNullForArbitraryKindString() {
        // Pin: even an entirely unknown kind returns null
        // (no throw). Drift to "throw on unknown" would
        // force every kind-dispatch site into try/catch.
        val unknown = SourceNode(id = SourceNodeId("u"), kind = "definitely-not-a-real-kind")
        assertNull(unknown.asVlogRawFootage())
        assertNull(unknown.asVlogEditIntent())
        assertNull(unknown.asVlogStylePreset())
    }

    // ── round-trip ──────────────────────────────────────────────

    @Test fun roundTripPreservesRawFootageBody() {
        val original = VlogRawFootageBody(
            assetIds = listOf(AssetId("clip-1"), AssetId("clip-2")),
            notes = "second take of the cake-cutting scene",
        )
        val source = Source.EMPTY.addVlogRawFootage(
            id = SourceNodeId("rf"),
            body = original,
        )
        val readBack = source.byId.getValue(SourceNodeId("rf")).asVlogRawFootage()
        assertEquals(original, readBack, "round-trip preserves all fields")
    }

    @Test fun roundTripPreservesEditIntentBodyWithOptionalsFilled() {
        val original = VlogEditIntentBody(
            description = "graduation day mood",
            targetDurationSeconds = 180,
            mood = "nostalgic",
        )
        val source = Source.EMPTY.addVlogEditIntent(SourceNodeId("ei"), original)
        val readBack = source.byId.getValue(SourceNodeId("ei")).asVlogEditIntent()
        assertEquals(original, readBack)
    }

    @Test fun roundTripPreservesEditIntentBodyWithOptionalsNull() {
        // Pin: optional fields default to null and roundtrip
        // correctly. Drift to "always populate with empty
        // string" would silently change the type contract.
        val original = VlogEditIntentBody(description = "freeform")
        val source = Source.EMPTY.addVlogEditIntent(SourceNodeId("ei-min"), original)
        val readBack = source.byId.getValue(SourceNodeId("ei-min")).asVlogEditIntent()
        assertEquals(original, readBack)
        assertNull(readBack!!.targetDurationSeconds, "optional field stays null")
        assertNull(readBack.mood)
    }

    @Test fun roundTripPreservesStylePresetWithFreeformParams() {
        // Pin: the params Map is freeform (Map<String, String>)
        // and roundtrips through the JsonElement encoding
        // with arbitrary keys + values intact. Drift to a
        // typed JsonObject with field-key restrictions would
        // silently filter unknown keys.
        val original = VlogStylePresetBody(
            name = "cinematic warm",
            params = mapOf(
                "saturation" to "0.85",
                "vignette" to "0.4",
                "custom_lut" to "asset-id-123",
            ),
        )
        val source = Source.EMPTY.addVlogStylePreset(SourceNodeId("sp"), original)
        val readBack = source.byId.getValue(SourceNodeId("sp")).asVlogStylePreset()
        assertEquals(original, readBack)
        assertEquals(3, readBack!!.params.size, "all params survive roundtrip")
    }

    // ── source mutation semantic ────────────────────────────────

    @Test fun multipleWritesAccumulateNodesInSource() {
        // Pin: each writer is `addNode`-based, so successive
        // calls accumulate (no in-place replacement).
        val source = Source.EMPTY
            .addVlogRawFootage(SourceNodeId("rf"), VlogRawFootageBody(assetIds = listOf(AssetId("a"))))
            .addVlogEditIntent(SourceNodeId("ei"), VlogEditIntentBody(description = "x"))
            .addVlogStylePreset(SourceNodeId("sp"), VlogStylePresetBody(name = "y"))
        assertEquals(3, source.nodes.size)
        assertEquals(setOf(SourceNodeId("rf"), SourceNodeId("ei"), SourceNodeId("sp")), source.byId.keys)
    }
}
