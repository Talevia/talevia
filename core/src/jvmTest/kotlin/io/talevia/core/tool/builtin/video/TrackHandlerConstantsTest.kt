package io.talevia.core.tool.builtin.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for two `internal` constants shared across the
 * track / transition handler family — `core/src/commonMain/.../video/`:
 *
 *  - [ACCEPTED_KINDS] (TrackActionHandlers.kt:241) — the set of
 *    valid `trackKind` values for `track_action(action="add")`.
 *  - [TRANSITION_ASSET_PREFIX] (TransitionActionHandlers.kt:38) —
 *    the canonical prefix that distinguishes transition pseudo-
 *    assets from real media asset ids.
 *
 * Cycle 262 audit: 0 test refs against either constant.
 *
 * Same audit-pattern fallback as cycles 207-261.
 *
 * Both constants are wire-format details — the user-facing
 * agent emits `trackKind="video"` / "audio" / "subtitle" /
 * "effect", and the export pipeline branches on
 * `assetId.startsWith("transition:")` to special-case
 * transition pseudo-assets vs real media assets. Drift in
 * either silently breaks dispatch / rendering for the affected
 * branch.
 *
 * Pins two correctness contracts:
 *
 *  1. **`ACCEPTED_KINDS` matches the `Track` sealed-class
 *     subtypes**: `Track.Video` / `Track.Audio` / `Track.Subtitle`
 *     / `Track.Effect` — exactly 4 entries. Drift to drop any
 *     kind would silently reject valid agent inputs (e.g.
 *     `track_action(action="add", trackKind="effect")` would
 *     fail). Drift to add an unrecognised kind would let the
 *     agent emit a kind the timeline can't render.
 *
 *  2. **`TRANSITION_ASSET_PREFIX = "transition:"`** verbatim.
 *     The export pipeline + agent help-text both branch on
 *     this exact prefix. Drift to "transition_" / "txn:" /
 *     etc. silently breaks the special-case detection.
 *
 * Plus structural invariants (lowercase / non-blank / etc.)
 * documenting what makes the values load-bearing.
 */
class TrackHandlerConstantsTest {

    // ── 1. ACCEPTED_KINDS ───────────────────────────────────

    @Test fun acceptedKindsHasExactlyFourEntries() {
        // Marquee count pin: 4 sealed subtypes of Track
        // (Video / Audio / Subtitle / Effect). Drift to add /
        // drop a kind silently changes accept/reject semantics
        // at dispatch.
        assertEquals(
            4,
            ACCEPTED_KINDS.size,
            "ACCEPTED_KINDS MUST have exactly 4 entries (matching the 4 Track sealed subtypes)",
        )
    }

    @Test fun acceptedKindsContainsCanonicalFourTrackKinds() {
        // Marquee canonical-set pin: the kinds match the
        // Track sealed-class subtype names lower-cased.
        assertEquals(
            setOf("video", "audio", "subtitle", "effect"),
            ACCEPTED_KINDS,
            "ACCEPTED_KINDS MUST be {video, audio, subtitle, effect} matching Track subtypes",
        )
    }

    @Test fun acceptedKindsAreLowercaseStrings() {
        // Marquee normalisation pin: per
        // `TrackActionHandlers.kt:44`, agent input is
        // lowercased before the `in ACCEPTED_KINDS` check.
        // Drift to mixed-case constants would silently break
        // the canonical agent input ("Video" / "VIDEO" emit).
        for (kind in ACCEPTED_KINDS) {
            assertEquals(
                kind.lowercase(),
                kind,
                "kind '$kind' MUST be lowercase to match the normalised input check",
            )
            assertTrue(
                kind.isNotBlank(),
                "kind MUST be non-blank (drift to empty would silently match agent's blank input)",
            )
        }
    }

    @Test fun acceptedKindsRejectsUppercaseDirectly() {
        // Pin: the constants are lowercase, so a direct
        // case-sensitive lookup of `"VIDEO"` returns false.
        // The dispatcher relies on this — it lowercases input
        // before checking `in`. Drift to "case-insensitive
        // contains" check at the constant level would let
        // `"Video"` pass without normalisation in dispatch.
        assertEquals(false, "VIDEO" in ACCEPTED_KINDS)
        assertEquals(false, "Video" in ACCEPTED_KINDS)
        assertEquals(false, "AUDIO" in ACCEPTED_KINDS)
    }

    @Test fun acceptedKindsRejectsTypos() {
        // Pin: defensive check — drift to add typo'd kind
        // would surface here.
        for (typo in listOf("vid", "aud", "captions", "fx", "vfx", "video-track", "audio_clip")) {
            assertEquals(
                false,
                typo in ACCEPTED_KINDS,
                "typo '$typo' MUST NOT be in ACCEPTED_KINDS",
            )
        }
    }

    // ── 2. TRANSITION_ASSET_PREFIX ──────────────────────────

    @Test fun transitionAssetPrefixIsCanonicalString() {
        // Marquee wire-format pin: drift to "transition_" /
        // "txn:" / etc. silently breaks export-pipeline /
        // agent-help-text special-case detection.
        assertEquals(
            "transition:",
            TRANSITION_ASSET_PREFIX,
            "TRANSITION_ASSET_PREFIX MUST be exactly 'transition:' verbatim",
        )
    }

    @Test fun transitionAssetPrefixEndsWithColonSeparator() {
        // Pin: the colon is the lexical separator between the
        // prefix and the per-transition uuid. Drift to drop
        // the colon would silently match real media asset ids
        // that happen to start with "transition" (no
        // separator).
        assertTrue(
            TRANSITION_ASSET_PREFIX.endsWith(":"),
            "prefix MUST end with ':' so it can't accidentally match real assets",
        )
    }

    @Test fun transitionAssetPrefixIsLowercaseAndNonBlank() {
        assertEquals(
            TRANSITION_ASSET_PREFIX.lowercase(),
            TRANSITION_ASSET_PREFIX,
            "prefix MUST be lowercase",
        )
        assertTrue(
            TRANSITION_ASSET_PREFIX.isNotBlank(),
            "prefix MUST be non-blank",
        )
    }

    @Test fun transitionAssetPrefixWorksAsStartsWithDiscriminator() {
        // Pin: the prefix is structured so a simple
        // `startsWith` discriminator separates transition
        // pseudo-assets from real assets. Real asset ids
        // don't start with "transition:" — drift to a more
        // ambiguous prefix would silently mis-classify.
        val transitionAssetId = "${TRANSITION_ASSET_PREFIX}fade-100ms-uuid-abc123"
        val realAssetId = "asset-1234"
        val anotherRealAssetId = "transition_typo:abc" // similar but wrong prefix

        assertTrue(
            transitionAssetId.startsWith(TRANSITION_ASSET_PREFIX),
            "transition pseudo-asset MUST be detected via startsWith",
        )
        assertEquals(
            false,
            realAssetId.startsWith(TRANSITION_ASSET_PREFIX),
            "real asset id MUST NOT match transition prefix",
        )
        assertEquals(
            false,
            anotherRealAssetId.startsWith(TRANSITION_ASSET_PREFIX),
            "near-miss prefix (underscore separator) MUST NOT match — silently mis-classifying it would break export",
        )
    }
}
