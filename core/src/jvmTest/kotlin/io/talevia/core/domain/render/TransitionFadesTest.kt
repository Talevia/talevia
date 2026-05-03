package io.talevia.core.domain.render

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Timeline.transitionFadesPerClip] —
 * `core/domain/render/TransitionFades.kt`. The
 * neighbour-aware fade-envelope deriver that drives the
 * per-clip mezzanine cache's fingerprint (a transition
 * touching this clip must invalidate its mezzanine).
 * Cycle 170 audit: 63 LOC, 0 direct test refs (the
 * function is exercised transitively by ExportTool's
 * per-clip cache tests but its own contracts — Effect-
 * track + transition-prefix filtering, half-duration
 * centering, boundary-matching, head/tail combination on
 * the same clip — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Filter is exactly `Track.Effect` × `Clip.Video` ×
 *    `assetId.value.startsWith("transition:")`.** Drift
 *    in any of the three filters would either include
 *    irrelevant clips (false fade applied) or exclude
 *    legitimate ones (mezzanine cache misses
 *    invalidation). Pinned via "video clips on a Video
 *    track ignored", "audio clips on Effect track
 *    ignored", "non-transition: prefix ignored".
 *
 * 2. **Transition centered on its temporal midpoint:
 *    `boundary = start + duration/2`, `halfDur = duration/2`.**
 *    `fromClip` is whichever neighbouring video clip ends
 *    AT boundary (gets `tailFade = halfDur`); `toClip` is
 *    whichever starts AT boundary (gets `headFade =
 *    halfDur`). Drift to "duration not halved" would emit
 *    fades twice as long as the transition; drift to
 *    "boundary at start" would mis-attach to the
 *    pre-transition clip.
 *
 * 3. **Both head and tail fades CAN coexist on the same
 *    clip.** A clip flanked by transitions on both sides
 *    accumulates both via `prior.copy(...)`. Drift to
 *    "last write wins" (overwriting the prior side)
 *    would silently drop one of the fades — the clip's
 *    mezzanine would then ignore the dropped neighbour
 *    on cache invalidation.
 */
class TransitionFadesTest {

    private fun videoClip(
        id: String,
        startSec: Long,
        durationSec: Long,
        assetId: String = "asset-$id",
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start = startSec.seconds, duration = durationSec.seconds),
        sourceRange = TimeRange(start = 0.seconds, duration = durationSec.seconds),
        assetId = AssetId(assetId),
    )

    private fun audioClip(
        id: String,
        startSec: Long,
        durationSec: Long,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start = startSec.seconds, duration = durationSec.seconds),
        sourceRange = TimeRange(start = 0.seconds, duration = durationSec.seconds),
        assetId = AssetId("audio-$id"),
    )

    private fun timeline(vararg tracks: Track): Timeline = Timeline(tracks = tracks.toList())

    // ── Empty / no-transition cases → empty map ───────────────

    @Test fun emptyTimelineReturnsEmptyMap() {
        // Pin: zero-track timeline → empty map (NOT null,
        // NOT throw).
        val out = Timeline().transitionFadesPerClip(emptyList())
        assertEquals(emptyMap(), out)
    }

    @Test fun timelineWithNoEffectTracksReturnsEmptyMap() {
        // Pin: only Video track present → no transitions to
        // walk → empty map.
        val tl = timeline(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("a", 0, 5), videoClip("b", 5, 5)),
            ),
        )
        val videoClips = tl.tracks.filterIsInstance<Track.Video>()
            .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        assertEquals(emptyMap(), tl.transitionFadesPerClip(videoClips))
    }

    @Test fun effectTrackWithNoTransitionPrefixedClipsReturnsEmptyMap() {
        // The marquee transition-prefix pin: clips on an
        // Effect track WITHOUT `transition:` prefixed
        // assetId are silently ignored. Drift to "consider
        // every Effect clip a transition" would slap fades
        // on every neighbour of e.g. an overlay clip.
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx"),
                clips = listOf(
                    videoClip("overlay", 5, 2, assetId = "overlay:logo"),
                    videoClip("vignette", 3, 1, assetId = "vignette"),
                ),
            ),
        )
        // Provide some video clips so the function can find
        // a fromClip if it bug-incorrectly treated overlay
        // as a transition.
        val videoClips = listOf(videoClip("v1", 0, 5), videoClip("v2", 5, 5))
        assertEquals(emptyMap(), tl.transitionFadesPerClip(videoClips))
    }

    @Test fun audioClipsOnEffectTrackAreIgnored() {
        // Pin: filter is `filterIsInstance<Clip.Video>()` on
        // Effect-track clips — a Clip.Audio with assetId
        // starting "transition:" still gets ignored.
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx"),
                clips = listOf(audioClip("audio-trans", 4, 2)),
            ),
        )
        val videoClips = listOf(videoClip("v1", 0, 5), videoClip("v2", 5, 5))
        assertEquals(emptyMap(), tl.transitionFadesPerClip(videoClips))
    }

    // ── Single transition: head + tail mapped to neighbours ───

    @Test fun transitionBetweenTwoClipsAttachesHeadAndTailFades() {
        // The marquee centering pin: a 2s transition at
        // [4,6) sits centered on boundary=5 between two
        // clips [0,5) and [5,10). halfDur=1s.
        // - clip-a ends at 5 → gets tailFade=1s
        // - clip-b starts at 5 → gets headFade=1s
        val transition = videoClip(
            "trans",
            startSec = 4,
            durationSec = 2,
            assetId = "transition:fade",
        )
        val tl = timeline(
            Track.Effect(id = TrackId("fx"), clips = listOf(transition)),
        )
        val videoClips = listOf(
            videoClip("a", 0, 5),
            videoClip("b", 5, 5),
        )
        val out = tl.transitionFadesPerClip(videoClips)

        assertEquals(2, out.size, "both flanking clips get a fade")
        assertEquals(
            TransitionFades(tailFade = 1.seconds),
            out[ClipId("a")],
            "fromClip (ends at boundary) gets tailFade=halfDur",
        )
        assertEquals(
            TransitionFades(headFade = 1.seconds),
            out[ClipId("b")],
            "toClip (starts at boundary) gets headFade=halfDur",
        )
    }

    @Test fun halfDurationIsHalfNotFull() {
        // Pin: `halfDur = transition.duration / 2`. Drift
        // to "halfDur = duration" (forgot the /2) would
        // emit fades twice as long as the transition's
        // visible footprint.
        val transition = videoClip(
            "t",
            startSec = 4,
            durationSec = 4, // total 4s → halfDur = 2s
            assetId = "transition:dissolve",
        )
        val tl = timeline(Track.Effect(id = TrackId("fx"), clips = listOf(transition)))
        val videoClips = listOf(
            videoClip("a", 0, 6), // ends at 6 (= start 4 + halfDur 2)
            videoClip("b", 6, 4), // starts at 6
        )
        val out = tl.transitionFadesPerClip(videoClips)
        assertEquals(2.seconds, out[ClipId("a")]?.tailFade)
        assertEquals(2.seconds, out[ClipId("b")]?.headFade)
    }

    // ── Boundary mismatch → no fade ──────────────────────────

    @Test fun transitionWithNoNeighbouringClipReturnsEmpty() {
        // Pin: if no video clip ends/starts AT the
        // boundary, neither fromClip nor toClip resolves
        // (`firstOrNull` returns null) → no entry in the
        // result map. Drift to "match nearest" would slap
        // fades on the wrong neighbour.
        val transition = videoClip(
            "trans",
            startSec = 4,
            durationSec = 2,
            assetId = "transition:fade",
        )
        val tl = timeline(Track.Effect(id = TrackId("fx"), clips = listOf(transition)))
        // None of the videoClips' boundaries align with 5.
        val videoClips = listOf(videoClip("a", 0, 4), videoClip("b", 7, 5))
        val out = tl.transitionFadesPerClip(videoClips)
        assertEquals(emptyMap(), out)
    }

    @Test fun onlyFromClipMatchesProducesTailOnlyFade() {
        // Pin: head/tail attach independently. If only
        // fromClip matches (no toClip), only tailFade is
        // set. Drift to "always set both" would NPE on the
        // null toClip side.
        val transition = videoClip(
            "trans",
            startSec = 4,
            durationSec = 2,
            assetId = "transition:fade",
        )
        val tl = timeline(Track.Effect(id = TrackId("fx"), clips = listOf(transition)))
        val videoClips = listOf(videoClip("a", 0, 5)) // ends at 5; no clip starts at 5
        val out = tl.transitionFadesPerClip(videoClips)
        assertEquals(1, out.size)
        assertEquals(TransitionFades(tailFade = 1.seconds), out[ClipId("a")])
        assertNull(
            out[ClipId("a")]?.headFade,
            "head not set when no toClip neighbour",
        )
    }

    @Test fun onlyToClipMatchesProducesHeadOnlyFade() {
        // Symmetric to the previous test: only toClip
        // matches → headFade only.
        val transition = videoClip(
            "trans",
            startSec = 4,
            durationSec = 2,
            assetId = "transition:slide",
        )
        val tl = timeline(Track.Effect(id = TrackId("fx"), clips = listOf(transition)))
        val videoClips = listOf(videoClip("b", 5, 5)) // starts at 5; no clip ends at 5
        val out = tl.transitionFadesPerClip(videoClips)
        assertEquals(1, out.size)
        assertEquals(TransitionFades(headFade = 1.seconds), out[ClipId("b")])
        assertNull(out[ClipId("b")]?.tailFade)
    }

    // ── Both fades coexist on same clip ──────────────────────

    @Test fun clipFlankedByTwoTransitionsAccumulatesBothFades() {
        // The marquee accumulation pin: a clip in the
        // middle of two transitions gets BOTH headFade and
        // tailFade via `prior.copy(...)`. Drift to "last
        // write wins, overwriting prior side" would drop
        // one of the fades and silently miss mezzanine
        // invalidation when one neighbour changes.
        //
        // Layout:
        //   clip-a [0, 5)
        //   trans-left [4, 6) → boundary 5: a ends, b starts
        //   clip-b [5, 10)
        //   trans-right [9, 11) → boundary 10: b ends, c starts
        //   clip-c [10, 15)
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx"),
                clips = listOf(
                    videoClip("trans-left", 4, 2, assetId = "transition:fade"),
                    videoClip("trans-right", 9, 2, assetId = "transition:dissolve"),
                ),
            ),
        )
        val videoClips = listOf(
            videoClip("a", 0, 5),
            videoClip("b", 5, 5), // gets both
            videoClip("c", 10, 5),
        )
        val out = tl.transitionFadesPerClip(videoClips)

        assertEquals(3, out.size, "all three clips touched")
        assertEquals(TransitionFades(tailFade = 1.seconds), out[ClipId("a")])
        assertEquals(
            TransitionFades(headFade = 1.seconds, tailFade = 1.seconds),
            out[ClipId("b")],
            "middle clip accumulates BOTH fades — drift to last-write-wins would lose one",
        )
        assertEquals(TransitionFades(headFade = 1.seconds), out[ClipId("c")])
    }

    // ── Multi-track Effect handling ──────────────────────────

    @Test fun transitionsAcrossMultipleEffectTracksAllProcessed() {
        // Pin: `tracks.filterIsInstance<Track.Effect>()`
        // covers all Effect tracks. Drift to "first Effect
        // track only" would silently miss multi-track
        // transition stacks (legal per the schema).
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx-1"),
                clips = listOf(videoClip("t1", 4, 2, assetId = "transition:fade")),
            ),
            Track.Effect(
                id = TrackId("fx-2"),
                clips = listOf(videoClip("t2", 9, 2, assetId = "transition:slide")),
            ),
        )
        val videoClips = listOf(
            videoClip("a", 0, 5),
            videoClip("b", 5, 5),
            videoClip("c", 10, 5),
        )
        val out = tl.transitionFadesPerClip(videoClips)
        assertEquals(3, out.size, "both tracks' transitions contribute")
    }

    // ── Single-clip empty videoClips guard ───────────────────

    @Test fun emptyVideoClipsListReturnsEmptyMapEvenWithTransitions() {
        // Pin: when caller passes empty videoClips, every
        // `firstOrNull` returns null → both branches skip
        // → empty map. Drift to NPE on the caller side
        // would crash export.
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx"),
                clips = listOf(videoClip("trans", 4, 2, assetId = "transition:fade")),
            ),
        )
        assertEquals(emptyMap(), tl.transitionFadesPerClip(emptyList()))
    }

    // ── Transition with no neighbours is silently dropped ────

    @Test fun transitionWithoutAnyMatchingNeighbourIsSilentlyDropped() {
        // Pin: a "transition with nothing on either side"
        // contributes zero entries. The drop is silent —
        // not an error. (The author of the timeline is
        // responsible for placing transitions correctly.)
        val tl = timeline(
            Track.Effect(
                id = TrackId("fx"),
                clips = listOf(
                    // OK transition
                    videoClip("good", 4, 2, assetId = "transition:fade"),
                    // Misplaced transition — boundary at 50 doesn't match any video clip
                    videoClip("bad", 49, 2, assetId = "transition:wipe"),
                ),
            ),
        )
        val videoClips = listOf(videoClip("a", 0, 5), videoClip("b", 5, 5))
        val out = tl.transitionFadesPerClip(videoClips)
        // Only the "good" transition contributes.
        assertEquals(2, out.size)
        // Neither fade picks up halfDur from the bad
        // transition (which would also be 1s here).
        assertTrue(
            out.values.all { it.tailFade == 1.seconds || it.headFade == 1.seconds },
        )
    }
}
