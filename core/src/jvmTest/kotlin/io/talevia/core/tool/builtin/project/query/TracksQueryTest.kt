package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runTracksQuery] — `project_query(select=
 * tracks)`. Per-track summary with clipCount + span derived
 * from clip extremes. Cycle 136 audit: 112 LOC, 0 transitive
 * test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Empty tracks have null span fields, NOT 0.0.** Per
 *    code: empty-clips path sets `firstClipStartSeconds`,
 *    `lastClipEndSeconds`, `spanSeconds` to null;
 *    `isEmpty=true`. A regression coalescing to 0.0 would let
 *    UI render "0..0s" placeholder instead of recognizing the
 *    empty state and showing "(empty)" properly.
 *
 * 2. **Span = lastEnd - firstStart, computed from clip
 *    extremes.** Pin the math: 3 clips at [0,5], [10,15],
 *    [20,30] → firstStart=0, lastEnd=30, span=30. A regression
 *    using sum-of-durations or any other calc would silently
 *    misreport timeline length.
 *
 * 3. **`onlyNonEmpty=true` filters out empty tracks (NOT
 *    null/false).** A regression filtering when null would
 *    silently drop empty tracks from default queries.
 */
class TracksQueryTest {

    private fun videoClip(id: String, start: Duration, duration: Duration) = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    private fun project(tracks: List<Track>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = tracks),
    )

    private fun input(
        trackKind: String? = null,
        sortBy: String? = null,
        onlyNonEmpty: Boolean? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_TRACKS,
        trackKind = trackKind,
        sortBy = sortBy,
        onlyNonEmpty = onlyNonEmpty,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<TrackRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(TrackRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun invalidTrackKindErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runTracksQuery(project(emptyList()), input(trackKind = "popcorn"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("trackKind must be one of" in msg, "got: $msg")
        // Lists all 4 valid kinds.
        assertTrue("video" in msg && "audio" in msg && "subtitle" in msg && "effect" in msg)
    }

    @Test fun invalidSortByErrorsLoud() {
        val ex = assertFailsWith<IllegalStateException> {
            runTracksQuery(project(emptyList()), input(sortBy = "popularity"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("sortBy for select=tracks" in msg, "got: $msg")
        assertTrue("index" in msg && "clipcount" in msg && "span" in msg && "recent" in msg)
    }

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyProjectReturnsZeroAndNoMatchMarker() {
        val result = runTracksQuery(project(emptyList()), input(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(emptyList(), decodeRows(result.data))
        assertTrue(
            "No tracks match the given filter" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    // ── empty-clips track has null span fields ───────────────────

    @Test fun emptyClipsTrackHasNullSpanFieldsAndIsEmptyTrue() {
        val emptyTrack = Track.Video(TrackId("vt"), emptyList())
        val rows = decodeRows(runTracksQuery(project(listOf(emptyTrack)), input(), 100, 0).data)
        val row = rows.single()
        assertEquals(true, row.isEmpty)
        assertEquals(0, row.clipCount)
        // Pin: ALL three span fields null (NOT 0.0).
        assertNull(row.firstClipStartSeconds, "firstStart null on empty")
        assertNull(row.lastClipEndSeconds, "lastEnd null on empty")
        assertNull(row.spanSeconds, "span null on empty")
    }

    // ── span math: lastEnd - firstStart ──────────────────────────

    @Test fun spanComputedAsLastEndMinusFirstStart() {
        // Marquee math pin: 3 clips at [0,5], [10,15], [20,30] →
        // firstStart=0, lastEnd=30, span=30 (NOT 5+5+10=20 sum
        // of durations, NOT 30 max-of-end).
        val track = Track.Video(
            TrackId("vt"),
            listOf(
                videoClip("c1", start = Duration.ZERO, duration = 5.seconds),
                videoClip("c2", start = 10.seconds, duration = 5.seconds),
                videoClip("c3", start = 20.seconds, duration = 10.seconds),
            ),
        )
        val rows = decodeRows(runTracksQuery(project(listOf(track)), input(), 100, 0).data)
        val row = rows.single()
        assertEquals(false, row.isEmpty)
        assertEquals(3, row.clipCount)
        assertEquals(0.0, row.firstClipStartSeconds)
        assertEquals(30.0, row.lastClipEndSeconds, "lastEnd = 20s + 10s = 30s")
        assertEquals(30.0, row.spanSeconds, "span = 30 - 0 = 30")
    }

    @Test fun firstAndLastFromClipExtremesNotInsertionOrder() {
        // Pin: firstStart = MIN of all clip starts, NOT first
        // clip in list order. lastEnd = MAX of clip ends.
        // Clips inserted in REVERSE time order to verify the
        // min/max scan.
        val track = Track.Video(
            TrackId("vt"),
            listOf(
                videoClip("late", start = 100.seconds, duration = 5.seconds),
                videoClip("early", start = Duration.ZERO, duration = 5.seconds),
            ),
        )
        val rows = decodeRows(runTracksQuery(project(listOf(track)), input(), 100, 0).data)
        val row = rows.single()
        // firstStart = 0 (from "early"), lastEnd = 105 (from "late").
        assertEquals(0.0, row.firstClipStartSeconds)
        assertEquals(105.0, row.lastClipEndSeconds)
        assertEquals(105.0, row.spanSeconds)
    }

    // ── filters: trackKind + onlyNonEmpty ────────────────────────

    @Test fun trackKindFilterRestrictsToMatchingKind() {
        val v = Track.Video(TrackId("v1"), emptyList())
        val a = Track.Audio(TrackId("a1"), emptyList())
        val s = Track.Subtitle(TrackId("s1"), emptyList())
        val e = Track.Effect(TrackId("e1"), emptyList())
        val all = listOf(v, a, s, e)
        for (kind in listOf("video", "audio", "subtitle", "effect")) {
            val rows = decodeRows(
                runTracksQuery(project(all), input(trackKind = kind), 100, 0).data,
            )
            assertEquals(1, rows.size, "exactly 1 track of kind=$kind")
            assertEquals(kind, rows.single().trackKind)
        }
    }

    @Test fun onlyNonEmptyTrueFiltersOutEmptyTracks() {
        val empty = Track.Video(TrackId("vt-empty"), emptyList())
        val full = Track.Video(
            TrackId("vt-full"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 5.seconds)),
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(empty, full)), input(onlyNonEmpty = true), 100, 0).data,
        )
        assertEquals(listOf("vt-full"), rows.map { it.trackId })
    }

    @Test fun onlyNonEmptyFalseDoesNotFilter() {
        // Pin: only `== true` triggers the filter. null AND
        // false both pass-through.
        val empty = Track.Video(TrackId("vt-empty"), emptyList())
        val full = Track.Video(
            TrackId("vt-full"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 5.seconds)),
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(empty, full)), input(onlyNonEmpty = false), 100, 0).data,
        )
        assertEquals(2, rows.size, "false → no filter")
    }

    @Test fun onlyNonEmptyNullDoesNotFilter() {
        val empty = Track.Video(TrackId("vt-empty"), emptyList())
        val full = Track.Video(
            TrackId("vt-full"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 5.seconds)),
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(empty, full)), input(), 100, 0).data,
        )
        assertEquals(2, rows.size, "null → no filter")
    }

    // ── sort modes ────────────────────────────────────────────────

    @Test fun defaultSortIsByIndex() {
        // No sortBy → preserves track index order.
        val tracks = listOf(
            Track.Video(TrackId("v1"), emptyList()),
            Track.Audio(TrackId("a1"), emptyList()),
            Track.Subtitle(TrackId("s1"), emptyList()),
        )
        val rows = decodeRows(runTracksQuery(project(tracks), input(), 100, 0).data)
        assertEquals(listOf("v1", "a1", "s1"), rows.map { it.trackId })
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
    }

    @Test fun sortByClipCountIsDescending() {
        val small = Track.Video(
            TrackId("small"),
            listOf(videoClip("c1", start = Duration.ZERO, duration = 5.seconds)),
        )
        val large = Track.Video(
            TrackId("large"),
            (1..3).map { videoClip("c$it", start = (it * 10).seconds, duration = 5.seconds) },
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(small, large)), input(sortBy = "clipcount"), 100, 0).data,
        )
        // Pin: descending → "large" (3 clips) before "small" (1 clip).
        assertEquals(listOf("large", "small"), rows.map { it.trackId })
    }

    @Test fun sortBySpanIsDescending() {
        val short = Track.Video(
            TrackId("short"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 5.seconds)),
        )
        val long = Track.Video(
            TrackId("long"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 100.seconds)),
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(short, long)), input(sortBy = "span"), 100, 0).data,
        )
        // Descending: long (100s) before short (5s).
        assertEquals(listOf("long", "short"), rows.map { it.trackId })
    }

    @Test fun sortBySpanTreatsEmptyTracksAsZeroSpan() {
        // Pin: empty tracks (null span) sort with `?: 0.0` → at
        // the bottom of the descending list. A regression
        // throwing on null would crash the query.
        val empty = Track.Video(TrackId("empty"), emptyList())
        val long = Track.Video(
            TrackId("long"),
            listOf(videoClip("c", start = Duration.ZERO, duration = 100.seconds)),
        )
        val rows = decodeRows(
            runTracksQuery(project(listOf(empty, long)), input(sortBy = "span"), 100, 0).data,
        )
        // long (100s) before empty (0).
        assertEquals(listOf("long", "empty"), rows.map { it.trackId })
    }

    // ── outputForLlm ──────────────────────────────────────────────

    @Test fun outputForEmptyTrackUsesEmptyMarkerInBullet() {
        // Pin bullet format: empty tracks render as
        // "- #<index> [<kind>/<id>] empty".
        val track = Track.Video(TrackId("vt"), emptyList())
        val out = runTracksQuery(project(listOf(track)), input(), 100, 0).outputForLlm
        assertTrue("- #0 [video/vt] empty" in out, "empty bullet; got: $out")
    }

    @Test fun outputForNonEmptyTrackShowsClipCountAndSpanRange() {
        // Pin bullet format: non-empty render as "- #<index>
        // [<kind>/<id>] <clipCount> clips, <firstStart>s..<lastEnd>s".
        val track = Track.Video(
            TrackId("vt"),
            listOf(
                videoClip("c1", start = Duration.ZERO, duration = 5.seconds),
                videoClip("c2", start = 10.seconds, duration = 5.seconds),
            ),
        )
        val out = runTracksQuery(project(listOf(track)), input(), 100, 0).outputForLlm
        assertTrue("- #0 [video/vt] 2 clips, 0.0s..15.0s" in out, "non-empty bullet; got: $out")
    }

    @Test fun outputHeaderIncludesProjectIdAndScopeLabel() {
        val track = Track.Video(TrackId("vt"), emptyList())
        val out = runTracksQuery(
            project(listOf(track)),
            input(trackKind = "video", onlyNonEmpty = false, sortBy = "index"),
            100,
            0,
        ).outputForLlm
        assertTrue("Project p" in out, "project id; got: $out")
        assertTrue("kind=video" in out, "kind scope; got: $out")
        assertTrue("sort=index" in out, "sort scope; got: $out")
        // onlyNonEmpty=false (not true) → no "non-empty" tag.
        assertTrue("non-empty" !in out, "false doesn't surface in scope; got: $out")
    }

    @Test fun outputHeaderHasNonEmptyTagWhenFilterActive() {
        val track = Track.Video(TrackId("vt"), emptyList())
        val out = runTracksQuery(
            project(listOf(track)),
            input(onlyNonEmpty = true),
            100,
            0,
        ).outputForLlm
        // onlyNonEmpty=true filters out the empty track →
        // 0 results + the "non-empty" scope tag.
        assertTrue("non-empty" in out, "got: $out")
    }

    @Test fun emptyResultStillShowsHeaderWithCounts() {
        val track = Track.Video(TrackId("vt"), emptyList())
        val out = runTracksQuery(project(listOf(track)), input(onlyNonEmpty = true), 100, 0).outputForLlm
        // Header: "Project p: 0/0 tracks, non-empty."
        assertTrue("0/0 tracks" in out, "0 of 0 after filter; got: $out")
        // No-match marker on body.
        assertTrue("No tracks match" in out, "got: $out")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsPageButTotalReflectsAllFiltered() {
        val tracks = (1..5).map { Track.Video(TrackId("vt$it"), emptyList()) }
        val result = runTracksQuery(project(tracks), input(), 2, 0)
        assertEquals(2, decodeRows(result.data).size)
        assertEquals(5, result.data.total)
    }

    @Test fun offsetSkipsFirstNRows() {
        val tracks = (1..5).map { Track.Video(TrackId("vt$it"), emptyList()) }
        val result = runTracksQuery(project(tracks), input(), 100, 2)
        // Default sort = index → vt1..vt5 in order; offset=2 →
        // start at vt3.
        assertEquals("vt3", decodeRows(result.data)[0].trackId)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runTracksQuery(project(emptyList()), input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_TRACKS, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val tracks = (1..5).map { Track.Video(TrackId("vt$it"), emptyList()) }
        val result = runTracksQuery(project(tracks), input(), 2, 0)
        assertTrue("(2/5)" in (result.title ?: ""), "title; got: ${result.title}")
    }

    @Test fun trackRowIndexFieldMirrorsTimelineTrackIndex() {
        // Pin: index = position in tracks list (0-based).
        val tracks = listOf(
            Track.Video(TrackId("first"), emptyList()),
            Track.Audio(TrackId("second"), emptyList()),
        )
        val rows = decodeRows(runTracksQuery(project(tracks), input(), 100, 0).data)
        assertEquals(0, rows[0].index)
        assertEquals(1, rows[1].index)
    }
}
