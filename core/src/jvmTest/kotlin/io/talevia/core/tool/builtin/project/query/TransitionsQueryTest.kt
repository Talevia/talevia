package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runTransitionsQuery] — `project_query(select=
 * transitions)`. Per-transition row with flanking video clip ids
 * recovered via midpoint-match within one-frame-at-30fps epsilon.
 * Cycle 137 audit: 117 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Transition detection: video clip on Effect track with
 *    "transition:" assetId prefix.** Per code: only Effect-track
 *    clips with `assetId.value.startsWith("transition:")`
 *    surface. Video-track clips never count (even if asset id
 *    starts "transition:"); non-Video clips on Effect tracks
 *    skip too. A regression broadening to Video tracks would
 *    surface every clip-with-transition-style-asset as if it
 *    were a transition.
 *
 * 2. **Flanking clip recovery uses midpoint-matching with
 *    EPSILON=34ms tolerance.** Per code: midpoint = (start +
 *    end) / 2; flanks are video clips whose end (for from) or
 *    start (for to) falls within 34ms of the midpoint. A
 *    regression tightening epsilon to 0 would silently miss
 *    flanks misaligned by frame-quantization slop; loosening
 *    would falsely match unrelated clips.
 *
 * 3. **Orphaned = neither flank found.** Per code:
 *    `orphaned = fromClip == null && toClip == null`. The
 *    `onlyOrphaned=true` filter restricts to these. Orphans are
 *    typically transitions left behind after both flanking
 *    clips were deleted/moved — the LLM uses this to advise
 *    "drop this orphan transition".
 */
class TransitionsQueryTest {

    private fun videoClip(
        id: String,
        assetId: String,
        start: Duration,
        duration: Duration,
        filters: List<Filter> = emptyList(),
    ) = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId(assetId),
        filters = filters,
    )

    private fun project(tracks: List<Track>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = tracks),
    )

    private fun input(onlyOrphaned: Boolean? = null) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_TRANSITIONS,
        onlyOrphaned = onlyOrphaned,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<TransitionRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(TransitionRow.serializer()),
            out.rows,
        )

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyProjectReportsZeroTransitions() {
        val result = runTransitionsQuery(project(emptyList()), input(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(emptyList(), decodeRows(result.data))
        assertTrue(
            "No transitions on this timeline" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    @Test fun videoTrackWithTransitionPrefixedAssetIsNotATransition() {
        // Marquee detection pin: only Effect-track clips count.
        // A clip on a Video track with "transition:" prefix is
        // ignored. A regression broadening to Video tracks would
        // surface every clip-with-prefix-asset as if it were a
        // transition.
        val faux = videoClip(
            id = "f1",
            assetId = "transition:fade",
            start = Duration.ZERO,
            duration = 1.seconds,
        )
        val result = runTransitionsQuery(
            project(listOf(Track.Video(TrackId("vt"), listOf(faux)))),
            input(),
            100,
            0,
        )
        assertEquals(0, result.data.total, "video-track clip ignored")
    }

    @Test fun effectTrackClipWithoutTransitionPrefixIsIgnored() {
        // Effect tracks can hold non-transition Clip.Video clips
        // (e.g. effects backed by other assets). Pin: only
        // "transition:"-prefixed asset ids count.
        val nonTransition = videoClip(
            id = "n1",
            assetId = "blur:filter",
            start = Duration.ZERO,
            duration = 1.seconds,
        )
        val result = runTransitionsQuery(
            project(listOf(Track.Effect(TrackId("et"), listOf(nonTransition)))),
            input(),
            100,
            0,
        )
        assertEquals(0, result.data.total)
    }

    // ── transition row shape ─────────────────────────────────────

    @Test fun transitionRowSurfacesNameDurationAndOrphaned() {
        // No flanking video clips → orphaned=true.
        val transition = videoClip(
            id = "t1",
            assetId = "transition:fade",
            start = 5.seconds,
            duration = 1.seconds,
        )
        val rows = decodeRows(
            runTransitionsQuery(
                project(listOf(Track.Effect(TrackId("et"), listOf(transition)))),
                input(),
                100,
                0,
            ).data,
        )
        val row = rows.single()
        assertEquals("t1", row.transitionClipId)
        assertEquals("et", row.trackId)
        // Pin: when filter is absent, transitionName falls back
        // to assetId stripped of "transition:" prefix.
        assertEquals("fade", row.transitionName)
        assertEquals(5.0, row.startSeconds)
        assertEquals(1.0, row.durationSeconds)
        assertEquals(6.0, row.endSeconds)
        assertEquals(true, row.orphaned, "no flanks → orphaned")
    }

    @Test fun transitionNameComesFromFirstFilterWhenPresent() {
        // Pin: when filters list non-empty, the first filter's
        // name takes precedence over the asset-id-derived name.
        val transition = videoClip(
            id = "t1",
            assetId = "transition:fade",
            start = Duration.ZERO,
            duration = 1.seconds,
            filters = listOf(Filter(name = "dissolve")),
        )
        val rows = decodeRows(
            runTransitionsQuery(
                project(listOf(Track.Effect(TrackId("et"), listOf(transition)))),
                input(),
                100,
                0,
            ).data,
        )
        // Pin: filter wins.
        assertEquals("dissolve", rows.single().transitionName)
    }

    // ── flanking clip recovery ───────────────────────────────────

    @Test fun bothFlanksRecoveredWhenAlignedAtTransitionMidpoint() {
        // Marquee pin: transition at [10s, 11s] → midpoint=10.5s.
        // From-clip ends at 10.5s; to-clip starts at 10.5s. Both
        // recovered.
        val from = videoClip(
            id = "from",
            assetId = "asset-from",
            start = Duration.ZERO,
            duration = 10.5.seconds,
        )
        val to = videoClip(
            id = "to",
            assetId = "asset-to",
            start = 10.5.seconds,
            duration = 5.seconds,
        )
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from, to)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        assertEquals("from", row.fromClipId, "from-clip resolved by midpoint match")
        assertEquals("to", row.toClipId, "to-clip resolved by midpoint match")
        assertEquals(false, row.orphaned, "both flanks → not orphaned")
    }

    @Test fun midpointMatchToleratesUpToThirtyFourMillisecondSlop() {
        // Pin: EPSILON = 34ms (one frame at 30fps + slop). A
        // from-clip ending 33ms before the midpoint still
        // matches. A clip ending 35ms before does NOT match.
        val midpoint = 10.5.seconds
        val from = videoClip(
            id = "from",
            assetId = "asset-from",
            start = Duration.ZERO,
            // End 33ms before midpoint → within EPSILON.
            duration = midpoint - 33.milliseconds,
        )
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        assertEquals("from", row.fromClipId, "33ms slop within epsilon → match")
    }

    @Test fun fromOnlyTransitionReportsToClipNullNotOrphaned() {
        // Pin: a transition with from but no to is NOT orphaned
        // (orphaned requires BOTH null).
        val from = videoClip(
            id = "from",
            assetId = "asset-from",
            start = Duration.ZERO,
            duration = 10.5.seconds,
        )
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        // No to-clip aligned with midpoint.
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        assertEquals("from", row.fromClipId)
        assertEquals(null, row.toClipId)
        assertEquals(false, row.orphaned, "single flank ≠ orphaned")
    }

    @Test fun toOnlyTransitionReportsFromClipNullNotOrphaned() {
        // Inverse of from-only: only to-clip aligned.
        val to = videoClip(
            id = "to",
            assetId = "asset-to",
            start = 10.5.seconds,
            duration = 5.seconds,
        )
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(to)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        assertEquals(null, row.fromClipId)
        assertEquals("to", row.toClipId)
        assertEquals(false, row.orphaned)
    }

    @Test fun orphanedFlagFiresOnlyWhenBothFlanksMissing() {
        // Pin: orphaned = (fromClip == null AND toClip == null).
        // Neither flank present → orphaned.
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        // Empty video track.
        val tracks = listOf(
            Track.Video(TrackId("vt"), emptyList()),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        assertEquals(true, row.orphaned)
    }

    @Test fun otherTransitionClipsAreSkippedWhenMatchingFlanks() {
        // Pin: the flanking-clip search filters out
        // "transition:" prefix clips, so a transition next to
        // another transition doesn't accidentally pair them up.
        val transitionPrefixedClip = videoClip(
            id = "fake-flank",
            assetId = "transition:something",
            start = 9.seconds,
            duration = 1.5.seconds, // ends at 10.5s — would match
        )
        val transition = videoClip(
            id = "tr",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(transitionPrefixedClip)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val row = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data).single()
        // Transition-prefixed clip excluded from flank search →
        // no from-clip found → orphaned.
        assertEquals(null, row.fromClipId, "transition-prefixed flanks excluded")
        assertEquals(true, row.orphaned)
    }

    // ── onlyOrphaned filter ──────────────────────────────────────

    @Test fun onlyOrphanedTrueRestrictsToOrphanedRows() {
        val from = videoClip(
            id = "from",
            assetId = "a-from",
            start = Duration.ZERO,
            duration = 10.5.seconds,
        )
        val to = videoClip(
            id = "to",
            assetId = "a-to",
            start = 10.5.seconds,
            duration = 5.seconds,
        )
        val withFlanks = videoClip(
            id = "tr-paired",
            assetId = "transition:fade",
            start = 10.seconds,
            duration = 1.seconds,
        )
        val orphan = videoClip(
            id = "tr-orphan",
            assetId = "transition:dissolve",
            start = 100.seconds,
            duration = 1.seconds,
        )
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from, to)),
            Track.Effect(TrackId("et"), listOf(withFlanks, orphan)),
        )
        val rows = decodeRows(
            runTransitionsQuery(project(tracks), input(onlyOrphaned = true), 100, 0).data,
        )
        // Pin: only the orphan surfaces.
        assertEquals(listOf("tr-orphan"), rows.map { it.transitionClipId })
    }

    @Test fun onlyOrphanedFalseDoesNotFilter() {
        // Pin: only `== true` triggers the filter; null and
        // false both pass-through.
        val from = videoClip("from", "a", Duration.ZERO, 10.5.seconds)
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds)
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val rows = decodeRows(
            runTransitionsQuery(project(tracks), input(onlyOrphaned = false), 100, 0).data,
        )
        // From-clip aligned → not orphaned. Still surfaces (no filter).
        assertEquals(1, rows.size)
        assertEquals(false, rows.single().orphaned)
    }

    // ── sort / pagination ─────────────────────────────────────────

    @Test fun rowsSortedByStartSecondsAscending() {
        // Pin: sortedBy startSeconds; insertion order doesn't
        // matter.
        val late = videoClip("late", "transition:a", 100.seconds, 1.seconds)
        val early = videoClip("early", "transition:b", 5.seconds, 1.seconds)
        val mid = videoClip("mid", "transition:c", 50.seconds, 1.seconds)
        val tracks = listOf(Track.Effect(TrackId("et"), listOf(late, early, mid)))
        val rows = decodeRows(runTransitionsQuery(project(tracks), input(), 100, 0).data)
        assertEquals(listOf("early", "mid", "late"), rows.map { it.transitionClipId })
    }

    @Test fun limitTrimsRowsButTotalReflectsAllFilteredCount() {
        val transitions = (1..5).map {
            videoClip("t$it", "transition:n$it", (it * 10).seconds, 1.seconds)
        }
        val tracks = listOf(Track.Effect(TrackId("et"), transitions))
        val result = runTransitionsQuery(project(tracks), input(), 2, 0)
        assertEquals(2, decodeRows(result.data).size)
        assertEquals(5, result.data.total)
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() {
        val transitions = (1..5).map {
            videoClip("t${it}", "transition:n$it", (it * 10).seconds, 1.seconds)
        }
        val tracks = listOf(Track.Effect(TrackId("et"), transitions))
        val result = runTransitionsQuery(project(tracks), input(), 100, 2)
        val rows = decodeRows(result.data)
        // Sorted by start: t1..t5; offset=2 → start at t3.
        assertEquals("t3", rows[0].transitionClipId)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun outputForLlmFormatHeaderAndPairBullets() {
        val from = videoClip("from", "a-from", Duration.ZERO, 10.5.seconds)
        val to = videoClip("to", "a-to", 10.5.seconds, 5.seconds)
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds)
        val orphan = videoClip("orph", "transition:dissolve", 100.seconds, 1.seconds)
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from, to)),
            Track.Effect(TrackId("et"), listOf(transition, orphan)),
        )
        val out = runTransitionsQuery(project(tracks), input(), 100, 0).outputForLlm
        // Pin header: "Project p: <returned> returned of <total>
        // total (<orphanCount> orphaned)<scope>."
        assertTrue("Project p:" in out, "header; got: $out")
        assertTrue("2 returned of 2 total" in out, "counts; got: $out")
        assertTrue("(1 orphaned)" in out, "orphan count; got: $out")
        // Pin paired bullet: "from → to".
        assertTrue("[from → to]" in out, "paired bullet; got: $out")
        // Pin orphan bullet: "[orphaned]".
        assertTrue("[orphaned]" in out, "orphan bullet; got: $out")
    }

    @Test fun outputForLlmHalfMissingFlankUsesMissingMarker() {
        // Pin: missing-from format "(missing) → to"; missing-to
        // format "from → (missing)".
        val from = videoClip("from", "a", Duration.ZERO, 10.5.seconds)
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds)
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val out = runTransitionsQuery(project(tracks), input(), 100, 0).outputForLlm
        // From present, to missing.
        assertTrue("[from → (missing)]" in out, "missing-to format; got: $out")
    }

    @Test fun outputForLlmMissingFromMarker() {
        val to = videoClip("to", "a", 10.5.seconds, 5.seconds)
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds)
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(to)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val out = runTransitionsQuery(project(tracks), input(), 100, 0).outputForLlm
        assertTrue("[(missing) → to]" in out, "missing-from format; got: $out")
    }

    @Test fun outputForLlmEmptyTimelineDistinctFromEmptyFilter() {
        // Pin: dual empty messages.
        // No transitions at all on timeline:
        val outNoTransitions = runTransitionsQuery(project(emptyList()), input(), 100, 0).outputForLlm
        assertTrue("No transitions on this timeline" in outNoTransitions, "got: $outNoTransitions")

        // Transitions exist but onlyOrphaned filter rejects all:
        val from = videoClip("from", "a", Duration.ZERO, 10.5.seconds)
        val to = videoClip("to", "b", 10.5.seconds, 5.seconds)
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds) // not orphan
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(from, to)),
            Track.Effect(TrackId("et"), listOf(transition)),
        )
        val outFiltered = runTransitionsQuery(
            project(tracks),
            input(onlyOrphaned = true),
            100,
            0,
        ).outputForLlm
        assertTrue("No transitions matched the filter" in outFiltered, "got: $outFiltered")
    }

    @Test fun outputForLlmIncludesScopeSuffixWhenOnlyOrphaned() {
        // Pin: scope label "(onlyOrphaned)" appears in the header
        // when the filter is active.
        val transition = videoClip("tr", "transition:fade", 10.seconds, 1.seconds)
        val tracks = listOf(Track.Effect(TrackId("et"), listOf(transition)))
        val out = runTransitionsQuery(
            project(tracks),
            input(onlyOrphaned = true),
            100,
            0,
        ).outputForLlm
        assertTrue("(onlyOrphaned)" in out, "scope suffix; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runTransitionsQuery(project(emptyList()), input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_TRANSITIONS, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val transitions = (1..5).map {
            videoClip("t$it", "transition:n$it", (it * 10).seconds, 1.seconds)
        }
        val tracks = listOf(Track.Effect(TrackId("et"), transitions))
        val result = runTransitionsQuery(project(tracks), input(), 2, 0)
        assertTrue("(2/5)" in (result.title ?: ""), "title; got: ${result.title}")
    }
}
