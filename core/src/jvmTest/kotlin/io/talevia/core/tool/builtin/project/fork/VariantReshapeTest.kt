package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ForkProjectTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [applyVariantSpec] —
 * `core/tool/builtin/project/fork/VariantReshape.kt`. The pure post-
 * fork timeline reshape function that applies a [ForkProjectTool.VariantSpec]
 * (aspectRatio + durationSecondsMax) to a freshly-forked Project.
 * Cycle 211 audit: 123 LOC, 0 direct test refs (only dispatcher-level
 * `ForkProjectToolTest` exercises one aspect preset + one duration cap).
 *
 * Same audit-pattern fallback as cycles 207-210. Direct symbol-level
 * testing adds value because the function is a pure data transform —
 * it can be exercised without seeding a full ProjectStore, and the
 * five aspect presets + edge cases of clip-vs-cap outcomes have meaningful
 * branch coverage gaps the dispatcher test never reaches.
 *
 * Six correctness contracts pinned:
 *
 *  1. **All 5 aspect-ratio presets resolve correctly** + unknown
 *     rejection. The aspectRatio rewrite touches BOTH
 *     `timeline.resolution` AND `outputProfile.resolution` (in
 *     lock-step — the export render spec follows the authoring grid
 *     when the aspect changes). Drift to "only timeline" or "only
 *     outputProfile" would either ship 16:9 exports of a 9:16 timeline
 *     or vice versa.
 *
 *  2. **`durationSecondsMax > 0` strictly.** Zero / negative is a
 *     malformed cap that would either drop everything (== 0) or
 *     never trip (< 0). Drift to "any positive Double" would let
 *     NaN through.
 *
 *  3. **Three clip outcomes from `cap`.** Per impl: `start >= cap`
 *     → dropped; `end <= cap` → kept whole; straddler → truncated
 *     (timeRange + sourceRange both, same delta). The clip-starts-
 *     exactly-at-cap case is `start >= cap` → dropped (NOT kept-
 *     and-empty). Pinning all three branches covers the impl's
 *     `for` loop.
 *
 *  4. **Text clips truncate timeRange ONLY (no sourceRange field).**
 *     Per impl's `applyDurationTrim` `when (clip)`: Video / Audio
 *     truncate both ranges in lock-step; Text has no sourceRange so
 *     only timeRange shrinks. Drift to "Text gets a synthetic
 *     sourceRange" would surface a NPE; drift to "Text truncates
 *     symmetrically" is impossible because Text has no sourceRange.
 *
 *  5. **Timeline duration capped to `min(current.duration, cap)`.**
 *     If the project's existing duration is already shorter than
 *     the cap, the cap is a no-op on duration (not "raises to cap").
 *     Pinning this prevents drift to `duration = cap` which would
 *     extend short projects to fake their length.
 *
 *  6. **Both axes optional + both compose.** `aspectRatio = null` +
 *     `durationSecondsMax = null` is a structural no-op (returns
 *     project unchanged + zero counts). Both set together should
 *     compose: aspect rewrite first, then duration cap (per impl
 *     order; truncation operates on whatever resolution the aspect
 *     rewrite set).
 *
 * Plus shape pins: returned `clipsDropped` / `clipsTruncated` counts
 * across multiple tracks (not just one); track variant types preserved
 * (Track.Audio stays Audio after reshape); cap unit semantics (Double
 * seconds → kotlin.time.Duration via `.seconds`).
 */
class VariantReshapeTest {

    private fun videoClip(
        id: String,
        timelineStart: Long,
        timelineDuration: Long,
        sourceStart: Long = 0,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(timelineStart.seconds, timelineDuration.seconds),
        sourceRange = TimeRange(sourceStart.seconds, timelineDuration.seconds),
        assetId = AssetId("asset-$id"),
    )

    private fun audioClip(
        id: String,
        timelineStart: Long,
        timelineDuration: Long,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(timelineStart.seconds, timelineDuration.seconds),
        sourceRange = TimeRange(0.seconds, timelineDuration.seconds),
        assetId = AssetId("audio-$id"),
    )

    private fun textClip(
        id: String,
        timelineStart: Long,
        timelineDuration: Long,
        text: String = "hi",
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(timelineStart.seconds, timelineDuration.seconds),
        text = text,
        style = TextStyle(),
    )

    private fun project(
        tracks: List<Track> = emptyList(),
        timelineDurationSec: Long = 0,
        timelineRes: Resolution = Resolution(1920, 1080),
        outputRes: Resolution = Resolution(1920, 1080),
    ): Project = Project(
        id = ProjectId("p-test"),
        timeline = Timeline(
            tracks = tracks,
            duration = timelineDurationSec.seconds,
            resolution = timelineRes,
        ),
        outputProfile = OutputProfile(
            resolution = outputRes,
            frameRate = FrameRate.FPS_30,
        ),
    )

    // ── 1. Aspect-ratio presets ─────────────────────────────

    @Test fun all5AspectRatioPresetsResolveCorrectly() = run {
        // Marquee preset pin: each documented preset maps to its
        // canonical (width, height). Drift in any value would silently
        // ship the wrong frame size.
        val cases = mapOf(
            "16:9" to Resolution(1920, 1080),
            "9:16" to Resolution(1080, 1920),
            "1:1" to Resolution(1080, 1080),
            "4:5" to Resolution(1080, 1350),
            "21:9" to Resolution(2520, 1080),
        )
        val baseline = project()
        for ((preset, expected) in cases) {
            val out = applyVariantSpec(
                baseline,
                ForkProjectTool.VariantSpec(aspectRatio = preset),
            )
            assertEquals(
                expected,
                out.project.timeline.resolution,
                "preset '$preset' → timeline.resolution",
            )
            assertEquals(
                expected,
                out.project.outputProfile.resolution,
                "preset '$preset' → outputProfile.resolution (lock-step)",
            )
        }
    }

    @Test fun aspectRatioPresetIsCaseInsensitiveAndTolerantOfWhitespace() = run {
        // Pin: per impl `aspect.trim().lowercase()`. Drift to "case
        // strict" would force the LLM to remember exact casing.
        val baseline = project()
        for (variant in listOf("16:9", "16:9 ", " 16:9 ", "\t16:9", "16:9")) {
            val out = applyVariantSpec(
                baseline,
                ForkProjectTool.VariantSpec(aspectRatio = variant),
            )
            assertEquals(Resolution(1920, 1080), out.project.timeline.resolution)
        }
    }

    @Test fun unknownAspectRatioRejectedWithEnumeratedHint() = run {
        // Pin: unknown preset fails loud with the accepted list cited.
        // Drift to "silently default to current" would land an aspect
        // mismatch agent never sees.
        val baseline = project()
        val ex = assertFailsWith<IllegalStateException> {
            applyVariantSpec(
                baseline,
                ForkProjectTool.VariantSpec(aspectRatio = "1080p"),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("'1080p' unknown" in msg, "expected unknown-aspect call-out; got: $msg")
        assertTrue(
            "16:9, 9:16, 1:1, 4:5, 21:9" in msg,
            "expected accepted-presets enumerated; got: $msg",
        )
    }

    @Test fun aspectRatioRewriteDoesNotTouchClips() = run {
        // Pin: aspect reshape preserves clip count and individual clip
        // ranges. Drift to "scale clips for aspect" would silently
        // letterbox / crop content the user didn't ask for.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(
                    videoClip("c1", 0, 5),
                    videoClip("c2", 5, 5),
                    videoClip("c3", 10, 5),
                ),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 15)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(aspectRatio = "9:16"),
        )
        assertEquals(0, out.clipsDropped)
        assertEquals(0, out.clipsTruncated)
        val clips = out.project.timeline.tracks.flatMap { it.clips }
        assertEquals(3, clips.size, "no clips dropped")
        assertEquals(15.seconds, out.project.timeline.duration, "duration untouched")
    }

    // ── 2. durationSecondsMax > 0 validation ────────────────

    @Test fun nonPositiveDurationCapRejected() = run {
        val baseline = project()
        for (bad in listOf(0.0, -1.0, -0.001)) {
            val ex = assertFailsWith<IllegalArgumentException> {
                applyVariantSpec(
                    baseline,
                    ForkProjectTool.VariantSpec(durationSecondsMax = bad),
                )
            }
            assertTrue(
                "durationSecondsMax must be > 0" in (ex.message ?: ""),
                "expected '> 0' message for $bad; got: ${ex.message}",
            )
        }
    }

    // ── 3. Three clip outcomes from cap ─────────────────────

    @Test fun clipBeforeCapIsKeptWhole() = run {
        // Pin: end <= cap → kept whole, no truncation.
        val tracks = listOf(
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", 0, 5))),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 5)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 10.0),
        )
        assertEquals(0, out.clipsDropped)
        assertEquals(0, out.clipsTruncated)
        val c1 = out.project.timeline.tracks[0].clips[0] as Clip.Video
        assertEquals(5.seconds, c1.timeRange.duration, "kept whole")
    }

    @Test fun clipAfterCapIsDropped() = run {
        // Pin: start >= cap → dropped (NOT kept-and-empty).
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("c1", 0, 2), videoClip("c2", 5, 2)),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 7)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(1, out.clipsDropped, "c2 (start=5 >= cap=4) dropped")
        assertEquals(0, out.clipsTruncated)
        val ids = out.project.timeline.tracks[0].clips.map { it.id.value }
        assertEquals(listOf("c1"), ids)
    }

    @Test fun clipStartingExactlyAtCapIsDropped() = run {
        // Marquee boundary pin: per impl `start >= cap` (NOT `start >
        // cap`). A clip whose timeline start equals the cap is
        // dropped, NOT kept as a zero-duration ghost. Drift to ">"
        // would land a clip with duration `cap - cap = 0`.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("c1", 0, 2), videoClip("c2", 4, 2)),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 6)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(1, out.clipsDropped, "c2 (start exactly == cap) dropped — NOT kept as 0-dur")
        assertEquals(listOf("c1"), out.project.timeline.tracks[0].clips.map { it.id.value })
    }

    @Test fun straddlerClipIsTruncatedTimelineAndSourceInLockStep() = run {
        // Pin: straddler (start < cap < end) truncates BOTH timeRange
        // and sourceRange by the same delta. Source-side truncation
        // matters because the ffmpeg render seeks into the asset; if
        // sourceRange weren't truncated in lock-step, the truncated
        // clip's playback would extend past its declared end.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("c1", 2, 5, sourceStart = 10)), // start=2, end=7, src 10-15
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 7)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(0, out.clipsDropped)
        assertEquals(1, out.clipsTruncated)
        val c1 = out.project.timeline.tracks[0].clips[0] as Clip.Video
        // newDuration = cap - start = 4 - 2 = 2.
        assertEquals(2.seconds, c1.timeRange.duration, "timeline duration cap-start")
        assertEquals(2.seconds, c1.timeRange.start, "timeline start preserved")
        assertEquals(2.seconds, c1.sourceRange.duration, "sourceRange duration matches timeline")
        assertEquals(
            10.seconds,
            c1.sourceRange.start,
            "sourceRange start preserved (only duration shrinks, NOT start)",
        )
    }

    @Test fun threeOutcomesCombinedInOneTrack() = run {
        // Pin: one track with [kept, straddler, dropped] all three
        // outcomes — counts and list match expectations.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(
                    videoClip("kept", 0, 2),
                    videoClip("straddler", 2, 3), // 2-5
                    videoClip("dropped", 6, 2),
                ),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 8)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(1, out.clipsDropped)
        assertEquals(1, out.clipsTruncated)
        val ids = out.project.timeline.tracks[0].clips.map { it.id.value }
        assertEquals(listOf("kept", "straddler"), ids)
        val straddler = out.project.timeline.tracks[0].clips[1] as Clip.Video
        assertEquals(2.seconds, straddler.timeRange.duration, "straddler trimmed to cap-start (4-2=2)")
    }

    @Test fun countsAccumulateAcrossMultipleTracks() = run {
        // Pin: dropped + truncated counts sum across ALL tracks. A
        // straddler on one track + a dropped on another → reported as
        // 1 + 1 in `VariantReshape`. Drift to "first-track-only" would
        // under-count.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("v-straddler", 2, 3)), // truncates
            ),
            Track.Audio(
                id = TrackId("a"),
                clips = listOf(audioClip("a-dropped", 6, 2)), // drops
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 8)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(1, out.clipsDropped, "drops sum across tracks")
        assertEquals(1, out.clipsTruncated, "truncations sum across tracks")
    }

    // ── 4. Text clips truncate timeRange only ───────────────

    @Test fun textClipStraddlerTruncatesTimeRangeOnly() = run {
        // Pin: per impl's `applyDurationTrim` Text branch — only
        // `timeRange` shrinks; Text has no sourceRange field. Drift
        // to "Text gets a synthetic sourceRange" would surface a NPE;
        // drift to "Text not truncated at all" would land a Text clip
        // extending past timeline.duration.
        val tracks = listOf(
            Track.Subtitle(
                id = TrackId("s"),
                clips = listOf(textClip("t1", 2, 3, text = "hello world")), // 2-5
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 5)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(1, out.clipsTruncated)
        val t1 = out.project.timeline.tracks[0].clips[0] as Clip.Text
        assertEquals(2.seconds, t1.timeRange.duration, "text timeline duration trimmed")
        assertEquals("hello world", t1.text, "text content preserved")
    }

    @Test fun audioClipStraddlerTruncatesBothRanges() = run {
        // Pin: Audio variant of `applyDurationTrim` mirrors Video —
        // both timeRange + sourceRange shrink. (Audio doesn't have a
        // separate truncation case despite different semantic
        // meaning; the same lock-step holds.)
        val tracks = listOf(
            Track.Audio(
                id = TrackId("a"),
                clips = listOf(audioClip("a1", 2, 3)),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 5)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        val a1 = out.project.timeline.tracks[0].clips[0] as Clip.Audio
        assertEquals(2.seconds, a1.timeRange.duration)
        assertEquals(2.seconds, a1.sourceRange.duration, "audio sourceRange trimmed in lock-step")
    }

    // ── 5. Timeline duration capped to min ──────────────────

    @Test fun timelineDurationCappedToCapWhenLonger() = run {
        // Pin: when the project's existing timeline.duration > cap,
        // the cap wins.
        val tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", 0, 10))))
        val baseline = project(tracks = tracks, timelineDurationSec = 10)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(4.seconds, out.project.timeline.duration)
    }

    @Test fun timelineDurationKeptShorterThanCap() = run {
        // Marquee min-not-set pin: when the project's existing
        // timeline.duration < cap, the duration STAYS short. Drift
        // to "raise to cap" would inflate short projects to fake
        // their length.
        val tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", 0, 2))))
        val baseline = project(tracks = tracks, timelineDurationSec = 2)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 10.0),
        )
        assertEquals(
            2.seconds,
            out.project.timeline.duration,
            "duration stays at 2s (min(2, 10) = 2) — NOT inflated to cap",
        )
    }

    // ── 6. Both axes optional + compose ─────────────────────

    @Test fun bothAxesNullIsStructuralNoOp() = run {
        // Pin: VariantSpec() with both fields null returns the project
        // unchanged + zero counts. The function is a single
        // composable transform; its identity element is the empty spec.
        val tracks = listOf(
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("c1", 0, 5))),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 5)
        val out = applyVariantSpec(baseline, ForkProjectTool.VariantSpec())
        assertEquals(baseline, out.project, "no-op spec returns baseline unchanged")
        assertEquals(0, out.clipsDropped)
        assertEquals(0, out.clipsTruncated)
    }

    @Test fun aspectAndDurationCompose() = run {
        // Pin: aspect rewrite + duration cap compose: aspect happens
        // first, duration cap applies on whatever resolution the aspect
        // set. Verify both effects survive together.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(
                    videoClip("c1", 0, 2),
                    videoClip("c2", 4, 2), // dropped at cap=4
                ),
            ),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 6)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(aspectRatio = "1:1", durationSecondsMax = 4.0),
        )
        assertEquals(Resolution(1080, 1080), out.project.timeline.resolution, "aspect applied")
        assertEquals(Resolution(1080, 1080), out.project.outputProfile.resolution)
        assertEquals(1, out.clipsDropped, "duration cap applied")
        assertEquals(4.seconds, out.project.timeline.duration)
    }

    // ── Track variant preservation pin ──────────────────────

    @Test fun trackVariantTypesPreservedAfterReshape() = run {
        // Pin: per impl's `withTrackClips` `when` over Track sealed:
        // each variant's type identity survives the reshape. Drift to
        // "all reshape to Track.Video" would erase Audio / Subtitle /
        // Effect track distinction.
        val tracks = listOf(
            Track.Video(id = TrackId("v"), clips = listOf(videoClip("vc", 0, 5))),
            Track.Audio(id = TrackId("a"), clips = listOf(audioClip("ac", 0, 5))),
            Track.Subtitle(id = TrackId("s"), clips = listOf(textClip("tc", 0, 5))),
            Track.Effect(id = TrackId("e"), clips = emptyList()),
        )
        val baseline = project(tracks = tracks, timelineDurationSec = 5)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        val byId = out.project.timeline.tracks.associateBy { it.id.value }
        assertTrue(byId["v"] is Track.Video)
        assertTrue(byId["a"] is Track.Audio)
        assertTrue(byId["s"] is Track.Subtitle)
        assertTrue(byId["e"] is Track.Effect)
    }

    @Test fun emptyTimelineWithDurationCapIsNoOp() = run {
        // Pin: zero-clip project with a duration cap doesn't crash;
        // returns zero counts.
        val baseline = project(timelineDurationSec = 0)
        val out = applyVariantSpec(
            baseline,
            ForkProjectTool.VariantSpec(durationSecondsMax = 4.0),
        )
        assertEquals(0, out.clipsDropped)
        assertEquals(0, out.clipsTruncated)
        assertEquals(0.seconds, out.project.timeline.duration)
    }
}
