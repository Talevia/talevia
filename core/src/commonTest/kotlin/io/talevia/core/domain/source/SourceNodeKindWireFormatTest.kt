package io.talevia.core.domain.source

import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.genre.ad.AdNodeKinds
import io.talevia.core.domain.source.genre.musicmv.MusicMvNodeKinds
import io.talevia.core.domain.source.genre.narrative.NarrativeNodeKinds
import io.talevia.core.domain.source.genre.tutorial.TutorialNodeKinds
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the wire-format kind strings used by every SourceNode-bearing
 * `talevia.json` bundle and every `source_query(kindPrefix=...)`
 * invocation. Cycle 295 audit:
 *
 *   - 5 genre kinds objects (Ad / Tutorial / Narrative / MusicMv /
 *     Vlog) — `find -name '*NodeKinds*Test*'` returns ZERO files.
 *   - `ConsistencyKinds` has [ConsistencyNodeTest] but only pins
 *     `.ALL.size == 4`, not the literal values of each constant.
 *
 * Same audit-pattern fallback as cycles 207-294. Applied
 * duplicate-check idiom (cycle 289) before drafting.
 *
 * Why this matters: these 17 strings are persisted into every
 * project bundle's `talevia.json`. Drift in ANY value silently
 * breaks bundle compatibility — old bundles can't decode, agents
 * dispatching `source_node_action(action="add", kind="ad.brand_brief")`
 * point at a different namespace.
 *
 * Drift signals:
 *   - **Namespace drift** (e.g. rename `core.consistency.*` →
 *     `core.consistency.v2.*`) silently breaks every existing
 *     bundle's consistency-fold.
 *   - **Casing drift** (e.g. `tutorial.broll_library` →
 *     `tutorial.brollLibrary`) silently breaks the
 *     lowercase-with-underscores wire-convention contract.
 *   - **Cross-namespace collision** (drift to two kinds sharing
 *     the same string) silently merges nodes across types.
 *   - **ConsistencyKinds.ALL drift** (drop a kind) silently
 *     reduces consistency-fold scope.
 *
 * Pins three contract families:
 *   1. **Exact value** for each of the 17 constants.
 *   2. **Genre-prefix invariant**: every constant in `XNodeKinds`
 *      starts with `<genre>.` prefix.
 *   3. **Cross-namespace uniqueness**: all 17 kinds are distinct.
 *   4. **ConsistencyKinds.ALL** equals exactly the 4 consistency
 *      strings.
 */
class SourceNodeKindWireFormatTest {

    // ── Per-genre exact value pins ──────────────────────────

    @Test fun adKindsExactValues() {
        // Marquee per-constant pin: drift to a different
        // namespace silently breaks every ad bundle.
        assertEquals("ad.brand_brief", AdNodeKinds.BRAND_BRIEF)
        assertEquals("ad.product_spec", AdNodeKinds.PRODUCT_SPEC)
        assertEquals("ad.variant_request", AdNodeKinds.VARIANT_REQUEST)
    }

    @Test fun tutorialKindsExactValues() {
        assertEquals("tutorial.script", TutorialNodeKinds.SCRIPT)
        assertEquals("tutorial.broll_library", TutorialNodeKinds.BROLL_LIBRARY)
        assertEquals("tutorial.brand_spec", TutorialNodeKinds.BRAND_SPEC)
    }

    @Test fun narrativeKindsExactValues() {
        assertEquals("narrative.world", NarrativeNodeKinds.WORLD)
        assertEquals("narrative.storyline", NarrativeNodeKinds.STORYLINE)
        assertEquals("narrative.scene", NarrativeNodeKinds.SCENE)
        assertEquals("narrative.shot", NarrativeNodeKinds.SHOT)
    }

    @Test fun musicMvKindsExactValues() {
        assertEquals("musicmv.track", MusicMvNodeKinds.TRACK)
        assertEquals("musicmv.visual_concept", MusicMvNodeKinds.VISUAL_CONCEPT)
        assertEquals("musicmv.performance_shot", MusicMvNodeKinds.PERFORMANCE_SHOT)
    }

    @Test fun vlogKindsExactValues() {
        assertEquals("vlog.raw_footage", VlogNodeKinds.RAW_FOOTAGE)
        assertEquals("vlog.edit_intent", VlogNodeKinds.EDIT_INTENT)
        assertEquals("vlog.style_preset", VlogNodeKinds.STYLE_PRESET)
    }

    @Test fun consistencyKindsExactValues() {
        // Marquee namespace pin: `core.consistency.*` is the
        // shared cross-genre namespace. Drift to a different
        // root would silently break every bundle's
        // consistency-fold.
        assertEquals("core.consistency.character_ref", ConsistencyKinds.CHARACTER_REF)
        assertEquals("core.consistency.style_bible", ConsistencyKinds.STYLE_BIBLE)
        assertEquals("core.consistency.brand_palette", ConsistencyKinds.BRAND_PALETTE)
        assertEquals("core.consistency.location_ref", ConsistencyKinds.LOCATION_REF)
    }

    // ── Genre-prefix invariant ──────────────────────────────

    @Test fun everyAdKindStartsWithAdDot() {
        // Pin: every ad kind starts with "ad." — drift to
        // mix in a non-prefixed kind would surface here.
        for (kind in listOf(
            AdNodeKinds.BRAND_BRIEF,
            AdNodeKinds.PRODUCT_SPEC,
            AdNodeKinds.VARIANT_REQUEST,
        )) {
            assertTrue(
                kind.startsWith("ad."),
                "Ad kind '$kind' MUST start with 'ad.' prefix",
            )
        }
    }

    @Test fun everyTutorialKindStartsWithTutorialDot() {
        for (kind in listOf(
            TutorialNodeKinds.SCRIPT,
            TutorialNodeKinds.BROLL_LIBRARY,
            TutorialNodeKinds.BRAND_SPEC,
        )) {
            assertTrue(kind.startsWith("tutorial."))
        }
    }

    @Test fun everyNarrativeKindStartsWithNarrativeDot() {
        for (kind in listOf(
            NarrativeNodeKinds.WORLD,
            NarrativeNodeKinds.STORYLINE,
            NarrativeNodeKinds.SCENE,
            NarrativeNodeKinds.SHOT,
        )) {
            assertTrue(kind.startsWith("narrative."))
        }
    }

    @Test fun everyMusicMvKindStartsWithMusicMvDot() {
        for (kind in listOf(
            MusicMvNodeKinds.TRACK,
            MusicMvNodeKinds.VISUAL_CONCEPT,
            MusicMvNodeKinds.PERFORMANCE_SHOT,
        )) {
            assertTrue(kind.startsWith("musicmv."))
        }
    }

    @Test fun everyVlogKindStartsWithVlogDot() {
        for (kind in listOf(
            VlogNodeKinds.RAW_FOOTAGE,
            VlogNodeKinds.EDIT_INTENT,
            VlogNodeKinds.STYLE_PRESET,
        )) {
            assertTrue(kind.startsWith("vlog."))
        }
    }

    @Test fun everyConsistencyKindStartsWithCoreConsistencyDot() {
        // Marquee shared-namespace pin: per the doc-comment,
        // namespace is `core.consistency.*` so genre extensions
        // can't accidentally redefine consistency kinds with
        // their own prefix.
        for (kind in ConsistencyKinds.ALL) {
            assertTrue(
                kind.startsWith("core.consistency."),
                "Consistency kind '$kind' MUST start with 'core.consistency.' prefix " +
                    "(genre-extension namespace-collision protection)",
            )
        }
    }

    // ── Wire-convention pins ────────────────────────────────

    @Test fun everyKindIsLowercaseWithUnderscores() {
        // Marquee convention pin: kinds use
        // lowercase-with-underscores (NOT camelCase, NOT
        // hyphens, NOT mixed). Drift would silently break
        // the kindPrefix wire convention.
        for (kind in allKinds()) {
            assertEquals(
                kind.lowercase(),
                kind,
                "kind '$kind' MUST be lowercase",
            )
            assertTrue(
                "-" !in kind,
                "kind '$kind' MUST NOT contain hyphens (use underscores)",
            )
            // Allowed chars: a-z, 0-9, '.', '_'.
            assertTrue(
                kind.all { it.isLetterOrDigit() || it == '.' || it == '_' },
                "kind '$kind' MUST contain only [a-z0-9._] characters",
            )
        }
    }

    @Test fun everyKindHasExactlyOneDotForGenreKindsAndTwoForConsistency() {
        // Pin: genre kinds are `<genre>.<kind>` (1 dot);
        // consistency kinds are `core.consistency.<kind>`
        // (2 dots). Drift to deeper namespacing would
        // surface here.
        for (kind in genreKinds()) {
            val dots = kind.count { it == '.' }
            assertEquals(
                1,
                dots,
                "genre kind '$kind' MUST have exactly 1 dot (got $dots)",
            )
        }
        for (kind in ConsistencyKinds.ALL) {
            val dots = kind.count { it == '.' }
            assertEquals(
                2,
                dots,
                "consistency kind '$kind' MUST have exactly 2 dots (core.consistency.<kind>; got $dots)",
            )
        }
    }

    @Test fun everyKindHasNonBlankSegmentAfterPrefix() {
        // Pin: nothing like `ad.` (trailing dot) or empty
        // segment. Drift to drop a kind name would surface
        // here.
        for (kind in allKinds()) {
            assertTrue(
                kind.split(".").all { it.isNotBlank() },
                "kind '$kind' MUST have non-blank segments",
            )
        }
    }

    // ── Cross-namespace uniqueness ─────────────────────────

    @Test fun allSeventeenKindsAreDistinct() {
        // Marquee uniqueness pin: 16 genre kinds + 4
        // consistency kinds = 20 distinct strings. Drift to
        // a duplicate would silently merge nodes across
        // types. (16 = 3+3+4+3+3 across 5 genres.)
        val all = allKinds()
        // Wait — 20 not 17. Recount: ad=3, tutorial=3,
        // narrative=4, musicmv=3, vlog=3 → 16 + consistency=4
        // → 20.
        assertEquals(
            20,
            all.size,
            "expected 20 total kind constants (16 genre + 4 consistency); got: ${all.size}",
        )
        assertEquals(
            all.size,
            all.toSet().size,
            "all kinds MUST be distinct; collisions: ${all.groupingBy { it }.eachCount().filter { it.value > 1 }}",
        )
    }

    @Test fun noGenreKindCollidesWithConsistencyNamespace() {
        // Pin: genre prefixes (ad / tutorial / narrative /
        // musicmv / vlog) don't accidentally use the
        // `core.consistency.` root.
        for (kind in genreKinds()) {
            assertTrue(
                !kind.startsWith("core.consistency."),
                "genre kind '$kind' MUST NOT use core.consistency.* namespace",
            )
        }
    }

    // ── ConsistencyKinds.ALL invariants ────────────────────

    @Test fun consistencyKindsAllSetIsExactlyTheFourValues() {
        // Marquee ALL-set pin: drift to drop one (or add a
        // new constant without registering it in ALL)
        // silently reduces consistency-fold scope. Sister
        // of [ConsistencyNodeTest.allConsistencyKindsIsFourMembers]
        // but pins the SET CONTENTS, not just size.
        assertEquals(
            setOf(
                "core.consistency.character_ref",
                "core.consistency.style_bible",
                "core.consistency.brand_palette",
                "core.consistency.location_ref",
            ),
            ConsistencyKinds.ALL,
            "ConsistencyKinds.ALL MUST contain exactly the 4 consistency wire strings",
        )
    }

    @Test fun consistencyKindsAllContainsEveryDeclaredConstant() {
        // Sister registration pin: every individual
        // CHARACTER_REF / STYLE_BIBLE / BRAND_PALETTE /
        // LOCATION_REF constant MUST be in ALL. Drift to
        // declare a new const but forget to add to ALL would
        // surface here.
        assertTrue(ConsistencyKinds.CHARACTER_REF in ConsistencyKinds.ALL)
        assertTrue(ConsistencyKinds.STYLE_BIBLE in ConsistencyKinds.ALL)
        assertTrue(ConsistencyKinds.BRAND_PALETTE in ConsistencyKinds.ALL)
        assertTrue(ConsistencyKinds.LOCATION_REF in ConsistencyKinds.ALL)
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun genreKinds(): List<String> = listOf(
        AdNodeKinds.BRAND_BRIEF, AdNodeKinds.PRODUCT_SPEC, AdNodeKinds.VARIANT_REQUEST,
        TutorialNodeKinds.SCRIPT, TutorialNodeKinds.BROLL_LIBRARY, TutorialNodeKinds.BRAND_SPEC,
        NarrativeNodeKinds.WORLD, NarrativeNodeKinds.STORYLINE,
        NarrativeNodeKinds.SCENE, NarrativeNodeKinds.SHOT,
        MusicMvNodeKinds.TRACK, MusicMvNodeKinds.VISUAL_CONCEPT, MusicMvNodeKinds.PERFORMANCE_SHOT,
        VlogNodeKinds.RAW_FOOTAGE, VlogNodeKinds.EDIT_INTENT, VlogNodeKinds.STYLE_PRESET,
    )

    private fun allKinds(): List<String> = genreKinds() + ConsistencyKinds.ALL.toList()
}
