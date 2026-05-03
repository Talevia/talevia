package io.talevia.core.domain.source

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Modality] / [modalityNeeds] —
 * `core/domain/source/Modality.kt`. The 2-variant enum +
 * sealed-class extension property that drives modality-
 * aware staleness in [io.talevia.core.domain.staleClipsFromLockfile]
 * (VISION §3.2 + §5.5). Cycle 168 audit: 44 LOC, 0 direct
 * test refs (the property is consumed transitively in
 * staleness tests but its own contracts — variant set,
 * Video→Visual, Audio→Audio, Text→Visual edge case —
 * were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`Modality` is exactly two variants: Visual + Audio.**
 *    Per kdoc: "Two-state on purpose: every clip kind we
 *    render today is one or the other. If a future clip
 *    kind genuinely needs both ... it can compare against
 *    both modalities and be stale-if-either — no enum
 *    addition needed." Drift to a 3rd variant ("AudioVisual")
 *    would silently change the staleness shape every
 *    consumer relies on.
 *
 * 2. **`Clip.Video.modalityNeeds == Modality.Visual` /
 *    `Clip.Audio.modalityNeeds == Modality.Audio`.** The
 *    happy-path mapping. Drift would invert staleness for
 *    every clip — image/video clips would be stale on
 *    voiceId edits (irrelevant), audio clips would be stale
 *    on visualDescription edits (irrelevant).
 *
 * 3. **`Clip.Text.modalityNeeds == Modality.Visual` (NOT
 *    Audio, NOT null).** The documented edge case: text
 *    overlays render into the visual frame. The kdoc
 *    explicitly explains this is "the property total over
 *    the sealed hierarchy" — drift to Audio would break
 *    the totality + invert any future Text-staleness
 *    semantics; drift to nullable would force every
 *    consumer to handle a third "no modality" case.
 */
class ModalityTest {

    private val anyRange = TimeRange(start = 0.seconds, duration = 1.seconds)

    // ── Modality enum shape ───────────────────────────────────

    @Test fun modalityEnumHasExactlyTwoVariants() {
        // Marquee variant-count pin. Drift to a 3rd would
        // silently widen every staleness check.
        assertEquals(2, Modality.entries.size)
        assertEquals(
            setOf(Modality.Visual, Modality.Audio),
            Modality.entries.toSet(),
        )
    }

    @Test fun modalityVariantsAreDistinct() {
        // Pin: enum equality keeps the variants
        // discriminable. Drift to ".equals()" override or
        // accidental aliasing would break consumers using
        // `when` exhaustiveness.
        assertTrue(Modality.Visual != Modality.Audio)
    }

    // ── Clip.Video → Visual ──────────────────────────────────

    @Test fun videoClipModalityIsVisual() {
        // Marquee mapping pin: a Clip.Video reads "visual"
        // staleness. Drift to Audio would silently mark
        // every video clip stale on voiceId edits.
        val clip: Clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = anyRange,
            sourceRange = anyRange,
            assetId = AssetId("a1"),
        )
        assertEquals(Modality.Visual, clip.modalityNeeds)
    }

    // ── Clip.Audio → Audio ───────────────────────────────────

    @Test fun audioClipModalityIsAudio() {
        // Marquee mapping pin: a Clip.Audio reads "audio"
        // staleness. Drift to Visual would silently mark
        // every audio clip stale on visualDescription edits.
        val clip: Clip = Clip.Audio(
            id = ClipId("c1"),
            timeRange = anyRange,
            sourceRange = anyRange,
            assetId = AssetId("a1"),
        )
        assertEquals(Modality.Audio, clip.modalityNeeds)
    }

    // ── Clip.Text → Visual (the documented edge case) ────────

    @Test fun textClipModalityIsVisualNotAudio() {
        // Marquee edge-case pin: text overlays render INTO
        // the visual frame, so the modality is Visual even
        // though Text doesn't carry an assetId. The kdoc
        // explicitly explains: "Text clips don't carry an
        // assetId and therefore have no lockfile entry, so
        // this branch is unreachable from the staleness
        // detector — kept here to make the property total
        // over the sealed hierarchy."
        //
        // A reflexive "text doesn't have visuals" rewrite
        // to Audio would silently break that totality + any
        // future Text-staleness expansion.
        val clip: Clip = Clip.Text(
            id = ClipId("c1"),
            timeRange = anyRange,
            text = "hello",
        )
        assertEquals(
            Modality.Visual,
            clip.modalityNeeds,
            "Clip.Text → Visual (NOT Audio): text renders into the visual frame",
        )
    }

    // ── totality over sealed hierarchy ───────────────────────

    @Test fun modalityNeedsIsTotalAcrossAllClipVariants() {
        // Pin: every concrete Clip subtype produces a non-
        // null Modality. Per kdoc this is "total over the
        // sealed hierarchy" — drift to nullable / partial
        // (e.g. throwing on Text) would break exhaustiveness
        // at every consumer's call site.
        val clips: List<Clip> = listOf(
            Clip.Video(
                id = ClipId("v"),
                timeRange = anyRange,
                sourceRange = anyRange,
                assetId = AssetId("av"),
            ),
            Clip.Audio(
                id = ClipId("a"),
                timeRange = anyRange,
                sourceRange = anyRange,
                assetId = AssetId("aa"),
            ),
            Clip.Text(
                id = ClipId("t"),
                timeRange = anyRange,
                text = "hi",
            ),
        )
        for (clip in clips) {
            // The property must be reachable for every
            // variant; explicit non-null assertion catches
            // any future @Throws drift.
            val modality: Modality = clip.modalityNeeds
            assertTrue(
                modality == Modality.Visual || modality == Modality.Audio,
                "totality: every clip resolves to a Modality variant",
            )
        }
    }

    // ── property is independent of clip metadata ─────────────

    @Test fun modalityNeedsIgnoresAssetIdAndDuration() {
        // Pin: modalityNeeds derives PURELY from the clip's
        // sealed type, NOT from its `assetId` / `timeRange`
        // / other fields. Two Clip.Video instances with
        // wildly different metadata both resolve to Visual.
        // Drift to "look at assetId.value to guess
        // modality" would couple Clip to AssetId
        // semantics.
        val v1: Clip = Clip.Video(
            id = ClipId("v1"),
            timeRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 1.seconds),
            assetId = AssetId("audio-sounding-id"), // confusingly named
        )
        val v2: Clip = Clip.Video(
            id = ClipId("v2"),
            timeRange = TimeRange(start = 100.seconds, duration = 30.seconds),
            sourceRange = TimeRange(start = 0.seconds, duration = 30.seconds),
            assetId = AssetId("img-1"),
        )
        assertEquals(Modality.Visual, v1.modalityNeeds, "asset-id text doesn't change modality")
        assertEquals(Modality.Visual, v2.modalityNeeds)
        assertEquals(v1.modalityNeeds, v2.modalityNeeds)
    }

    @Test fun modalityNeedsIgnoresTextContent() {
        // Pin: text content doesn't change modality. Drift
        // to "if text mentions audio, return Audio" would
        // be silly but is the kind of micro-optimization
        // someone might attempt.
        val short: Clip = Clip.Text(id = ClipId("t1"), timeRange = anyRange, text = "")
        val long: Clip = Clip.Text(
            id = ClipId("t2"),
            timeRange = anyRange,
            text = "an audio waveform of music ".repeat(20),
        )
        assertEquals(Modality.Visual, short.modalityNeeds)
        assertEquals(Modality.Visual, long.modalityNeeds)
    }
}
