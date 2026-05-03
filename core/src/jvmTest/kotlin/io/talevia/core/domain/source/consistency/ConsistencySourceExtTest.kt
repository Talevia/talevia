package io.talevia.core.domain.source.consistency

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the consistency genre's typed builders +
 * accessors AND its source-level resolution helpers in
 * `core/domain/source/consistency/ConsistencySourceExt.kt`.
 * 4 writer/reader pairs + 2 source-level resolution
 * functions ([Source.consistencyNodes] and
 * [Source.resolveConsistencyBindings]). Cycle 151 audit:
 * 130 LOC, 0 transitive test refs.
 *
 * The four kinds (character_ref / style_bible /
 * brand_palette / location_ref) live in Core (under
 * `core.consistency.*`) rather than under a genre directory
 * because they're shared cross-genre — a character in a
 * narrative short is structurally identical to a performer
 * in an MV or a subject in a vlog (per kdoc).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Each writer stamps a `core.consistency.<kind>`
 *    constant.** Distinct from genre exts which use
 *    `<genre>.<kind>` — these live in Core's namespace
 *    because the abstractions are cross-genre.
 *
 * 2. **`consistencyNodes()` filters the source to consistency
 *    kinds only, preserving declaration order.** A regression
 *    that returned all nodes would silently mix narrative /
 *    vlog / non-consistency kinds into folding paths,
 *    corrupting AIGC prompt assembly.
 *
 * 3. **`resolveConsistencyBindings(ids)` silently drops
 *    missing or non-consistency ids — never throws.** Per
 *    kdoc: "AIGC tool errors on stale bindings would just
 *    surface as opaque prompt garbage; better to skip with a
 *    warning than to crash the generation." Drift to
 *    throwing on missing-id would propagate up to crash the
 *    enclosing tool dispatch, breaking generation for any
 *    project with stale references.
 */
class ConsistencySourceExtTest {

    // ── kind constants ──────────────────────────────────────────

    @Test fun kindConstantsUseCoreConsistencyNamespace() {
        // Pin: namespace is `core.consistency.*` (NOT a
        // genre prefix). The kdoc explicitly justifies this:
        // these are cross-genre abstractions, not a single
        // genre's schema.
        assertTrue(ConsistencyKinds.CHARACTER_REF.startsWith("core.consistency."))
        assertTrue(ConsistencyKinds.STYLE_BIBLE.startsWith("core.consistency."))
        assertTrue(ConsistencyKinds.BRAND_PALETTE.startsWith("core.consistency."))
        assertTrue(ConsistencyKinds.LOCATION_REF.startsWith("core.consistency."))
    }

    @Test fun allConstantSetMatchesIndividualConstants() {
        // Pin: ConsistencyKinds.ALL is a Set of exactly the
        // 4 declared constants. A regression that omitted a
        // constant from ALL (e.g. forgot to add LOCATION_REF
        // when it landed) would silently exclude that kind
        // from `consistencyNodes()` filtering AND
        // `resolveConsistencyBindings()` resolution.
        assertEquals(
            setOf(
                ConsistencyKinds.CHARACTER_REF,
                ConsistencyKinds.STYLE_BIBLE,
                ConsistencyKinds.BRAND_PALETTE,
                ConsistencyKinds.LOCATION_REF,
            ),
            ConsistencyKinds.ALL,
        )
    }

    // ── writers stamp correct kind ──────────────────────────────

    @Test fun addCharacterRefStampsCharacterRefKind() {
        val source = Source.EMPTY.addCharacterRef(
            id = SourceNodeId("c1"),
            body = CharacterRefBody(name = "Mei", visualDescription = "tall, short black hair"),
        )
        assertEquals(ConsistencyKinds.CHARACTER_REF, source.byId.getValue(SourceNodeId("c1")).kind)
    }

    @Test fun addStyleBibleStampsStyleBibleKind() {
        val source = Source.EMPTY.addStyleBible(
            id = SourceNodeId("s1"),
            body = StyleBibleBody(name = "warm cinema", description = "1970s 35mm grain"),
        )
        assertEquals(ConsistencyKinds.STYLE_BIBLE, source.byId.getValue(SourceNodeId("s1")).kind)
    }

    @Test fun addBrandPaletteStampsBrandPaletteKind() {
        val source = Source.EMPTY.addBrandPalette(
            id = SourceNodeId("b1"),
            body = BrandPaletteBody(name = "acme", hexColors = listOf("#0A84FF", "#FF3B30")),
        )
        assertEquals(ConsistencyKinds.BRAND_PALETTE, source.byId.getValue(SourceNodeId("b1")).kind)
    }

    @Test fun addLocationRefStampsLocationRefKind() {
        val source = Source.EMPTY.addLocationRef(
            id = SourceNodeId("l1"),
            body = LocationRefBody(name = "the cafe", description = "small, sunlit"),
        )
        assertEquals(ConsistencyKinds.LOCATION_REF, source.byId.getValue(SourceNodeId("l1")).kind)
    }

    // ── readers null on kind mismatch ──────────────────────────

    @Test fun readersReturnNullForCrossKindAndCrossGenreMismatches() {
        val source = Source.EMPTY.addCharacterRef(
            id = SourceNodeId("c"),
            body = CharacterRefBody(name = "x", visualDescription = "y"),
        )
        val node = source.byId.getValue(SourceNodeId("c"))
        // Same-genre cross-kind null.
        assertNull(node.asStyleBible())
        assertNull(node.asBrandPalette())
        assertNull(node.asLocationRef())
        // Cross-genre kind never matches consistency readers.
        val foreign = SourceNode(id = SourceNodeId("f"), kind = "vlog.raw_footage")
        assertNull(foreign.asCharacterRef())
        assertNull(foreign.asStyleBible())
        assertNull(foreign.asBrandPalette())
        assertNull(foreign.asLocationRef())
    }

    // ── round-trip per body type ────────────────────────────────

    @Test fun roundTripPreservesCharacterRefBodyIncludingLoraPin() {
        // Pin: the LoraPin nested data class roundtrips through
        // the JsonElement encoding without flattening or
        // dropping fields.
        val original = CharacterRefBody(
            name = "Mei",
            visualDescription = "tall, short black hair, kind eyes",
            referenceAssetIds = listOf(AssetId("ref-1"), AssetId("ref-2")),
            loraPin = LoraPin(
                adapterId = "civitai:42",
                weight = 0.85f,
                triggerTokens = listOf("Mei", "<lora:mei:0.85>"),
            ),
            voiceId = "elevenlabs-uuid-mei",
        )
        val source = Source.EMPTY.addCharacterRef(SourceNodeId("c"), original)
        assertEquals(original, source.byId.getValue(SourceNodeId("c")).asCharacterRef())
    }

    @Test fun roundTripPreservesStyleBibleBody() {
        val original = StyleBibleBody(
            name = "gritty handheld",
            description = "documentary, low contrast",
            lutReference = AssetId("lut-1"),
            negativePrompt = "no soft focus",
            moodKeywords = listOf("frenetic", "raw"),
        )
        val source = Source.EMPTY.addStyleBible(SourceNodeId("s"), original)
        assertEquals(original, source.byId.getValue(SourceNodeId("s")).asStyleBible())
    }

    @Test fun roundTripPreservesBrandPaletteHexColorsInOrder() {
        // Pin: `hexColors` order matters — first is canonical
        // (the kdoc names this explicitly). Roundtrip must
        // preserve order, not just set membership.
        val original = BrandPaletteBody(
            name = "acme",
            hexColors = listOf("#0A84FF", "#FF3B30", "#34C759"),
            typographyHints = listOf("Helvetica Neue", "SF Pro"),
        )
        val source = Source.EMPTY.addBrandPalette(SourceNodeId("b"), original)
        val readBack = source.byId.getValue(SourceNodeId("b")).asBrandPalette()!!
        assertEquals(original, readBack)
        assertEquals("#0A84FF", readBack.hexColors[0], "primary stays at index 0")
    }

    // ── consistencyNodes() filtering ───────────────────────────

    @Test fun consistencyNodesReturnsOnlyConsistencyKindsInDeclarationOrder() {
        // The marquee filter pin: source with mixed kinds —
        // consistency + foreign — only the consistency 4
        // surface, in the order they were added.
        val source = Source.EMPTY
            .addCharacterRef(SourceNodeId("c"), CharacterRefBody("a", "b"))
            // Inject a foreign kind directly via SourceNode
            // construction (no genre ext available here).
            .let { it.copy(nodes = it.nodes + SourceNode(id = SourceNodeId("foreign"), kind = "vlog.raw_footage")) }
            .addStyleBible(SourceNodeId("s"), StyleBibleBody("a", "b"))
            .addBrandPalette(SourceNodeId("b"), BrandPaletteBody("a", listOf("#fff")))
            .addLocationRef(SourceNodeId("l"), LocationRefBody("a", "b"))

        val consistency = source.consistencyNodes()
        // Pin: foreign filtered out.
        assertEquals(4, consistency.size, "vlog.raw_footage filtered out; got: ${consistency.map { it.id.value }}")
        // Pin: declaration order preserved within
        // consistency-only filter.
        assertEquals(
            listOf("c", "s", "b", "l"),
            consistency.map { it.id.value },
            "declaration order preserved across filter",
        )
    }

    @Test fun consistencyNodesEmptySourceReturnsEmpty() {
        assertEquals(emptyList(), Source.EMPTY.consistencyNodes())
    }

    @Test fun consistencyNodesAllForeignReturnsEmpty() {
        // Pin: source with only foreign kinds → empty list,
        // not throw.
        val source = Source(
            nodes = listOf(
                SourceNode(id = SourceNodeId("a"), kind = "vlog.raw_footage"),
                SourceNode(id = SourceNodeId("b"), kind = "narrative.scene"),
            ),
        )
        assertEquals(emptyList(), source.consistencyNodes())
    }

    // ── resolveConsistencyBindings() silent-drop semantics ────

    @Test fun resolveConsistencyBindingsEmptyInputReturnsEmpty() {
        // Pin: empty input short-circuits BEFORE iterating.
        val source = Source.EMPTY.addCharacterRef(SourceNodeId("c"), CharacterRefBody("a", "b"))
        assertEquals(emptyList(), source.resolveConsistencyBindings(emptyList()))
    }

    @Test fun resolveConsistencyBindingsResolvesValidIdsToTheirNodes() {
        val source = Source.EMPTY
            .addCharacterRef(SourceNodeId("c1"), CharacterRefBody("a", "b"))
            .addStyleBible(SourceNodeId("s1"), StyleBibleBody("a", "b"))

        val resolved = source.resolveConsistencyBindings(
            listOf(SourceNodeId("c1"), SourceNodeId("s1")),
        )
        assertEquals(2, resolved.size)
        assertEquals(setOf("c1", "s1"), resolved.map { it.id.value }.toSet())
    }

    @Test fun resolveConsistencyBindingsDropsMissingIdsSilently() {
        // The marquee silent-drop pin: stale binding (id
        // doesn't exist anymore) → silently dropped from the
        // output, NOT an exception. The kdoc-rationale: "AIGC
        // tool errors on stale bindings would just surface as
        // opaque prompt garbage; better to skip with a
        // warning than to crash the generation."
        val source = Source.EMPTY
            .addCharacterRef(SourceNodeId("c"), CharacterRefBody("a", "b"))
        val resolved = source.resolveConsistencyBindings(
            listOf(
                SourceNodeId("c"),
                SourceNodeId("ghost"), // not in source
                SourceNodeId("vanished"), // also missing
            ),
        )
        // Pin: only the present id surfaces. No throw.
        assertEquals(1, resolved.size)
        assertEquals("c", resolved.single().id.value)
    }

    @Test fun resolveConsistencyBindingsDropsNonConsistencyIdsSilently() {
        // Pin: id resolves to a node, but that node is a
        // non-consistency kind (vlog / narrative / etc.) →
        // silently dropped. Bindings can stale across schema
        // changes (a former character_ref became a
        // narrative.scene); the resolver tolerates without
        // crashing.
        val source = Source(
            nodes = listOf(
                SourceNode(id = SourceNodeId("c"), kind = ConsistencyKinds.CHARACTER_REF),
                SourceNode(id = SourceNodeId("v"), kind = "vlog.raw_footage"),
                SourceNode(id = SourceNodeId("n"), kind = "narrative.scene"),
            ),
        )
        val resolved = source.resolveConsistencyBindings(
            listOf(SourceNodeId("c"), SourceNodeId("v"), SourceNodeId("n")),
        )
        // Pin: only the consistency-kind one survives.
        assertEquals(1, resolved.size)
        assertEquals("c", resolved.single().id.value)
    }

    @Test fun resolveConsistencyBindingsAllInvalidReturnsEmptyNotThrow() {
        // Pin: all-invalid input is the worst-case; must
        // still return empty (NOT throw, NOT return null).
        val source = Source.EMPTY.addCharacterRef(
            SourceNodeId("c"),
            CharacterRefBody("a", "b"),
        )
        val resolved = source.resolveConsistencyBindings(
            listOf(SourceNodeId("ghost-1"), SourceNodeId("ghost-2")),
        )
        assertEquals(emptyList(), resolved)
    }
}
