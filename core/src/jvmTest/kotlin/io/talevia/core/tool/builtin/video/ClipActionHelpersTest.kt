package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for the pure helpers in
 * `core/tool/builtin/video/ClipActionHelpers.kt`. Cycle 216 audit:
 * 139 LOC of small stateless helpers shared by the 12 verbs of
 * [ClipActionTool] (split out in the cycle 44
 * `debt-tool-consolidation-clip-action-phase1`). Each helper is
 * exercised indirectly through dispatcher tests, but never pinned
 * at the symbol level — drift in the sealed `when` matches would
 * surface as a confusing test failure several layers up. Pinning
 * here catches it at the helper.
 *
 * Same audit-pattern fallback as cycles 207-215.
 *
 * Six correctness contracts pinned:
 *
 *  1. **`pickVideoTrack` resolution + fallback.** Explicit trackId
 *     resolves only to a Video track; mismatched variant fails
 *     loud. No requestedId picks the first Video track in order.
 *     Empty / no-video tracks list returns a freshly minted
 *     `Track.Video(random uuid)` — drift to "throw" would break
 *     `add` flows on empty timelines.
 *
 *  2. **`splitClip` left/right invariants.** left + right combined
 *     reconstruct the original timeline range. Video / Audio
 *     additionally split `sourceRange` in lock-step (preserving
 *     ffmpeg seek-into-asset coherence); Text has no sourceRange so
 *     only timeRange splits. Both sides get fresh ids — drift to
 *     "share id" would break downstream uniqueness invariants.
 *
 *  3. **`isKindCompatible` matrix.** Video×Video, Audio×Audio,
 *     Text×Subtitle → true. Every other combination → false.
 *     Notably: Effect tracks accept NO clip kind (no branch in the
 *     impl; falls through to false for all). Drift to "Text on Audio
 *     OK" would silently land subtitles on the wrong track type.
 *
 *  4. **`withTrim` Text-rejection.** Video / Audio trim copies
 *     timeRange + sourceRange. Text → fail loud with "cannot trim
 *     a text clip" (Text has no sourceRange to trim against; trim
 *     semantics are media-only).
 *
 *  5. **`replaceClip` sorts by timeRange.start.** Replaces matching
 *     id then re-sorts by start. Drift to "preserve insertion
 *     order" would let an out-of-order replace produce a non-
 *     monotonic clip list (the Track invariant per
 *     `Track.kt` kdoc requires ordered-by-start).
 *
 *  6. **`recomputeClipBoundDuration` = max(end) across tracks.**
 *     Empty timeline → Duration.ZERO. Multi-track timeline → max
 *     of all clip ends across all track variants. Drift to
 *     "first-track only" or "sum of durations" would mis-compute
 *     timeline.duration on multi-track edits.
 *
 * Plus shape pins: `cloneClip` preserves duration; `shiftStart`
 * is purely additive on start (no duration change); per-variant
 * round-trip for `withClips` / `withTimeRange`; `clipKindOf` /
 * `trackKindOf` string mappings.
 */
class ClipActionHelpersTest {

    private fun videoClip(
        id: String,
        start: Long = 0,
        duration: Long = 5,
        sourceStart: Long = 0,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, duration.seconds),
        sourceRange = TimeRange(sourceStart.seconds, duration.seconds),
        assetId = AssetId("a-$id"),
    )

    private fun audioClip(
        id: String,
        start: Long = 0,
        duration: Long = 5,
    ): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, duration.seconds),
        sourceRange = TimeRange(0.seconds, duration.seconds),
        assetId = AssetId("audio-$id"),
    )

    private fun textClip(
        id: String,
        start: Long = 0,
        duration: Long = 5,
        text: String = "hi",
    ): Clip.Text = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, duration.seconds),
        text = text,
        style = TextStyle(),
    )

    // ── 1. pickVideoTrack ───────────────────────────────────

    @Test fun pickVideoTrackResolvesExplicitIdToVideoTrack() {
        val v = Track.Video(id = TrackId("v1"))
        val a = Track.Audio(id = TrackId("a1"))
        assertEquals(v, pickVideoTrack(listOf(a, v), requestedId = "v1"))
    }

    @Test fun pickVideoTrackRejectsMismatchedVariant() {
        // Pin: explicit trackId pointing at an Audio track fails
        // loud. Drift to "silently fall back to first Video" would
        // mask the agent's typo.
        val tracks = listOf(
            Track.Audio(id = TrackId("a1")),
            Track.Video(id = TrackId("v1")),
        )
        val ex = assertFailsWith<IllegalStateException> {
            pickVideoTrack(tracks, requestedId = "a1")
        }
        assertTrue("trackId a1 is not a video track" in (ex.message ?: ""))
    }

    @Test fun pickVideoTrackRejectsUnknownId() {
        val tracks = listOf(Track.Video(id = TrackId("v1")))
        val ex = assertFailsWith<IllegalStateException> {
            pickVideoTrack(tracks, requestedId = "ghost")
        }
        assertTrue("trackId ghost not found" in (ex.message ?: ""))
    }

    @Test fun pickVideoTrackNullIdPicksFirstVideo() {
        val tracks = listOf(
            Track.Audio(id = TrackId("a1")),
            Track.Video(id = TrackId("v1")),
            Track.Video(id = TrackId("v2")),
        )
        // First Video in iteration order is v1.
        assertEquals(TrackId("v1"), pickVideoTrack(tracks, requestedId = null).id)
    }

    @Test fun pickVideoTrackMintsFreshTrackWhenNoVideoExists() {
        // Marquee fallback pin: empty / no-video tracks list →
        // returns a fresh Track.Video with random uuid id, NOT a
        // throw. This is what makes `add` work on an empty
        // timeline.
        val tracks = listOf(Track.Audio(id = TrackId("a1")))
        val out = pickVideoTrack(tracks, requestedId = null)
        assertTrue(out.clips.isEmpty(), "minted track is empty")
        assertNotEquals(
            TrackId("a1"),
            out.id,
            "minted track has a fresh id (NOT reused from existing tracks)",
        )
        assertTrue(out.id.value.isNotEmpty(), "minted id is non-empty")
    }

    @Test fun pickVideoTrackEmptyTracksMintsFreshTrack() {
        val out = pickVideoTrack(emptyList(), requestedId = null)
        assertTrue(out.clips.isEmpty())
        assertTrue(out.id.value.isNotEmpty())
    }

    // ── 2. splitClip ─────────────────────────────────────────

    @Test fun splitClipVideoSplitsTimeRangeAndSourceRangeInLockStep() {
        val orig = videoClip("orig", start = 10, duration = 8, sourceStart = 100)
        val (left, right) = splitClip(orig, offset = 3.seconds)
        // left: timeRange=(10, 3), sourceRange=(100, 3)
        assertEquals(TimeRange(10.seconds, 3.seconds), left.timeRange)
        assertEquals(TimeRange(100.seconds, 3.seconds), (left as Clip.Video).sourceRange)
        // right: timeRange=(13, 5), sourceRange=(103, 5)
        assertEquals(TimeRange(13.seconds, 5.seconds), right.timeRange)
        assertEquals(TimeRange(103.seconds, 5.seconds), (right as Clip.Video).sourceRange)
        // Combined: 8 seconds total, lock-step.
        assertEquals(orig.timeRange.duration, left.timeRange.duration + right.timeRange.duration)
        assertEquals(orig.sourceRange.duration, left.sourceRange.duration + right.sourceRange.duration)
    }

    @Test fun splitClipFreshIdsForBothSides() {
        // Marquee fresh-id pin: both sides get new ids, distinct
        // from the original AND from each other. Drift to "share
        // original id" would break downstream uniqueness invariants.
        val orig = videoClip("orig", duration = 6)
        val (left, right) = splitClip(orig, offset = 2.seconds)
        assertNotEquals(orig.id, left.id, "left id ≠ original id")
        assertNotEquals(orig.id, right.id, "right id ≠ original id")
        assertNotEquals(left.id, right.id, "left id ≠ right id")
    }

    @Test fun splitClipTextSplitsTimeRangeOnly() {
        // Pin: Text has no sourceRange field; only timeRange splits.
        // Drift to "synthesize sourceRange" would fail at compile —
        // covered defensively here.
        val orig = textClip("t1", start = 0, duration = 10)
        val (left, right) = splitClip(orig, offset = 4.seconds)
        assertEquals(TimeRange(0.seconds, 4.seconds), left.timeRange)
        assertEquals(TimeRange(4.seconds, 6.seconds), right.timeRange)
        // Both sides preserve text content.
        assertEquals("hi", (left as Clip.Text).text)
        assertEquals("hi", (right as Clip.Text).text)
    }

    @Test fun splitClipAudioSplitsBothRanges() {
        val orig = audioClip("a1", start = 0, duration = 10)
        val (left, right) = splitClip(orig, offset = 4.seconds)
        assertEquals(4.seconds, left.timeRange.duration)
        assertEquals(4.seconds, (left as Clip.Audio).sourceRange.duration)
        assertEquals(6.seconds, right.timeRange.duration)
        assertEquals(6.seconds, (right as Clip.Audio).sourceRange.duration)
    }

    // ── 3. isKindCompatible matrix ──────────────────────────

    @Test fun isKindCompatibleMatrix() {
        val vc = videoClip("vc")
        val ac = audioClip("ac")
        val tc = textClip("tc")
        val vt = Track.Video(id = TrackId("v"))
        val at = Track.Audio(id = TrackId("a"))
        val st = Track.Subtitle(id = TrackId("s"))
        val et = Track.Effect(id = TrackId("e"))

        // Compatible pairs.
        assertTrue(isKindCompatible(vc, vt), "Video clip × Video track")
        assertTrue(isKindCompatible(ac, at), "Audio clip × Audio track")
        assertTrue(isKindCompatible(tc, st), "Text clip × Subtitle track")

        // Cross-pair incompatibility (full 9 cells).
        assertEquals(false, isKindCompatible(vc, at), "Video×Audio")
        assertEquals(false, isKindCompatible(vc, st), "Video×Subtitle")
        assertEquals(false, isKindCompatible(ac, vt), "Audio×Video")
        assertEquals(false, isKindCompatible(ac, st), "Audio×Subtitle")
        assertEquals(false, isKindCompatible(tc, vt), "Text×Video")
        assertEquals(false, isKindCompatible(tc, at), "Text×Audio")

        // Effect track accepts NOTHING (no branch in impl).
        assertEquals(false, isKindCompatible(vc, et), "Video×Effect")
        assertEquals(false, isKindCompatible(ac, et), "Audio×Effect")
        assertEquals(false, isKindCompatible(tc, et), "Text×Effect")
    }

    // ── 4. withTrim Text-rejection ──────────────────────────

    @Test fun withTrimVideoCopiesBothRanges() {
        val orig = videoClip("v1", start = 0, duration = 10, sourceStart = 100)
        val newTimeline = TimeRange(2.seconds, 5.seconds)
        val newSource = TimeRange(105.seconds, 5.seconds)
        val out = withTrim(orig, newTimeline, newSource) as Clip.Video
        assertEquals(newTimeline, out.timeRange)
        assertEquals(newSource, out.sourceRange)
        assertEquals(orig.id, out.id, "id preserved")
        assertEquals(orig.assetId, out.assetId, "assetId preserved")
    }

    @Test fun withTrimAudioCopiesBothRanges() {
        val orig = audioClip("a1", duration = 10)
        val newTimeline = TimeRange(0.seconds, 3.seconds)
        val newSource = TimeRange(0.seconds, 3.seconds)
        val out = withTrim(orig, newTimeline, newSource) as Clip.Audio
        assertEquals(newTimeline, out.timeRange)
        assertEquals(newSource, out.sourceRange)
    }

    @Test fun withTrimTextRejected() {
        // Marquee Text-rejection pin: Text has no sourceRange so
        // trim semantics don't apply.
        val orig = textClip("t1", duration = 5)
        val ex = assertFailsWith<IllegalStateException> {
            withTrim(
                orig,
                timelineRange = TimeRange(0.seconds, 3.seconds),
                sourceRange = TimeRange(0.seconds, 3.seconds),
            )
        }
        assertTrue("cannot trim a text clip" in (ex.message ?: ""))
    }

    // ── 5. replaceClip sorts by timeRange.start ─────────────

    @Test fun replaceClipReplacesMatchingIdAndSortsByStart() {
        // Marquee sort pin: `replaceClip` re-sorts the clips list
        // by timeRange.start after the swap. Drift to "preserve
        // insertion order" would let an out-of-order replace
        // produce a non-monotonic clip list (Track invariant
        // requires ordered-by-start per the kdoc on Track).
        val track = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                videoClip("c1", start = 0, duration = 5),
                videoClip("c2", start = 5, duration = 5),
                videoClip("c3", start = 10, duration = 5),
            ),
        )
        // Replace c2 with a clip that starts at 20 — should land
        // last after the sort.
        val replacement = videoClip("c2", start = 20, duration = 5)
        val out = replaceClip(track, replacement)
        val ids = out.clips.map { it.id.value }
        assertEquals(listOf("c1", "c3", "c2"), ids, "list re-sorted by start")
        // Replaced clip has the new start.
        assertEquals(20.seconds, (out.clips.last() as Clip.Video).timeRange.start)
    }

    @Test fun replaceClipNonMatchingIdLeavesListUnchanged() {
        // Pin: per impl `if (it.id == replacement.id) replacement
        // else it` — no match means no swap. The list is still
        // sorted (idempotent on already-sorted input).
        val track = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                videoClip("c1", start = 0, duration = 5),
                videoClip("c2", start = 5, duration = 5),
            ),
        )
        val ghost = videoClip("ghost", start = 100, duration = 5)
        val out = replaceClip(track, ghost)
        assertEquals(2, out.clips.size, "no clip added")
        assertEquals(listOf("c1", "c2"), out.clips.map { it.id.value })
    }

    // ── 6. recomputeClipBoundDuration ───────────────────────

    @Test fun recomputeClipBoundDurationEmptyTimelineIsZero() {
        assertEquals(Duration.ZERO, recomputeClipBoundDuration(emptyList()))
    }

    @Test fun recomputeClipBoundDurationTakesMaxAcrossTracks() {
        // Marquee multi-track pin: max(end) across ALL tracks. A
        // 7-second video clip on track A and a 12-second audio
        // clip on track B → 12s overall. Drift to "first-track
        // only" would under-report.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(videoClip("vc", start = 0, duration = 7)),
            ),
            Track.Audio(
                id = TrackId("a"),
                clips = listOf(audioClip("ac", start = 0, duration = 12)),
            ),
        )
        assertEquals(12.seconds, recomputeClipBoundDuration(tracks))
    }

    @Test fun recomputeClipBoundDurationLongClipStartingMidTimeline() {
        // Pin: end = start + duration. A clip starting at 10s for
        // 15s ends at 25s.
        val tracks = listOf(
            Track.Video(
                id = TrackId("v"),
                clips = listOf(
                    videoClip("c1", start = 0, duration = 5),
                    videoClip("c2", start = 10, duration = 15),
                ),
            ),
        )
        assertEquals(25.seconds, recomputeClipBoundDuration(tracks))
    }

    @Test fun recomputeClipBoundDurationSkipsEmptyTracks() {
        // Pin: a track with no clips contributes nothing; the max
        // is computed over present clips only. Drift to "include
        // empty track as 0" wouldn't matter (max(...,0) is the
        // same), but drift to "throw on empty track" would break
        // a freshly-created project.
        val tracks = listOf(
            Track.Video(id = TrackId("empty")),
            Track.Audio(
                id = TrackId("a"),
                clips = listOf(audioClip("ac", start = 0, duration = 8)),
            ),
        )
        assertEquals(8.seconds, recomputeClipBoundDuration(tracks))
    }

    // ── Smaller helpers: trackKindOf / clipKindOf ───────────

    @Test fun trackKindOfEachVariantStringMapping() {
        assertEquals("video", trackKindOf(Track.Video(id = TrackId("v"))))
        assertEquals("audio", trackKindOf(Track.Audio(id = TrackId("a"))))
        assertEquals("subtitle", trackKindOf(Track.Subtitle(id = TrackId("s"))))
        assertEquals("effect", trackKindOf(Track.Effect(id = TrackId("e"))))
    }

    @Test fun clipKindOfEachVariantStringMapping() {
        assertEquals("video", clipKindOf(videoClip("v")))
        assertEquals("audio", clipKindOf(audioClip("a")))
        assertEquals("text", clipKindOf(textClip("t")))
    }

    // ── cloneClip ───────────────────────────────────────────

    @Test fun cloneClipPreservesDurationAndSourceRange() {
        // Pin: `cloneClip` only changes id + timeRange.start. The
        // duration is preserved (matches original.timeRange.duration);
        // sourceRange is unchanged (Video / Audio); Text has no
        // sourceRange so unchanged trivially.
        val orig = videoClip("orig", start = 5, duration = 8, sourceStart = 100)
        val cloned = cloneClip(orig, ClipId("clone"), newStart = 50.seconds) as Clip.Video
        assertEquals(ClipId("clone"), cloned.id)
        assertEquals(50.seconds, cloned.timeRange.start)
        assertEquals(8.seconds, cloned.timeRange.duration, "duration preserved from original")
        assertEquals(orig.sourceRange, cloned.sourceRange, "sourceRange unchanged on clone")
    }

    // ── shiftStart extension ────────────────────────────────

    @Test fun shiftStartIsAdditiveOnStartPreservingDuration() {
        // Pin: shiftStart adds delta to timeRange.start; duration
        // unchanged. Negative delta is allowed (shift backward).
        val orig = videoClip("v", start = 10, duration = 5)
        val shifted = orig.shiftStart(3.seconds) as Clip.Video
        assertEquals(13.seconds, shifted.timeRange.start)
        assertEquals(5.seconds, shifted.timeRange.duration, "duration unchanged")
    }

    // ── withClips / withTimeRange ───────────────────────────

    @Test fun withClipsReplacesClipsListAcrossAllTrackVariants() {
        val newClips = listOf(videoClip("c1"))
        // Use a Video clip on Video track for type compat. The
        // helper itself doesn't check kind — that's `isKindCompatible`'s
        // job.
        for (track in listOf(
            Track.Video(id = TrackId("v")),
            Track.Audio(id = TrackId("a")),
            Track.Subtitle(id = TrackId("s")),
            Track.Effect(id = TrackId("e")),
        )) {
            val out = withClips(track, newClips)
            assertEquals(track::class, out::class, "track variant preserved for ${track.id.value}")
            assertEquals(newClips, out.clips)
        }
    }

    @Test fun withTimeRangePreservesClipVariantAndAssetId() {
        // Pin: withTimeRange copies the clip with new timeRange,
        // preserving variant + every other field (id / assetId /
        // sourceRange / etc.).
        val newRange = TimeRange(99.seconds, 1.seconds)
        val v = videoClip("v1", start = 0, duration = 5, sourceStart = 100)
        val vOut = withTimeRange(v, newRange) as Clip.Video
        assertEquals(newRange, vOut.timeRange)
        assertEquals(v.id, vOut.id, "id preserved")
        assertEquals(v.assetId, vOut.assetId)
        assertEquals(v.sourceRange, vOut.sourceRange, "sourceRange untouched")
    }
}
