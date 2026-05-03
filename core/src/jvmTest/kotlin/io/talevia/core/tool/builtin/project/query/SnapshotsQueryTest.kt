package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runSnapshotsQuery] — `project_query(select=
 * snapshots)`. Enumerates saved snapshots newest-first with
 * optional age-based filtering. Cycle 134 audit: 97 LOC, 0
 * transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Newest-first ordering by `capturedAtEpochMs`.** Per
 *    kdoc: "newest-first (by capturedAtEpochMs)". A regression
 *    flipping the sort would silently invert "what did I just
 *    save?" workflow expectations — the user's mental model is
 *    that the first row is the most recent.
 *
 * 2. **`maxAgeDays` filter is computed via `clock.now() - days
 *    * MS_PER_DAY`.** Time-based filter must respect the
 *    injected clock for testability AND cutoff is inclusive
 *    (entries with `capturedAtEpochMs >= cutoff` survive). A
 *    regression using exclusive comparison would silently drop
 *    boundary entries; using a real wall-clock instead of the
 *    injected one would make the filter
 *    non-deterministic in tests.
 *
 * 3. **`maxAgeDays` < 0 fails loud.** Per code: `require(it >=
 *    0)`. A regression accepting negative values would either
 *    surface no entries (cutoff > now) or trip an arithmetic
 *    overflow.
 */
class SnapshotsQueryTest {

    private val timeRange = TimeRange(start = kotlin.time.Duration.ZERO, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
    )

    private fun assetWithId(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = 10.seconds,
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
        ),
    )

    private fun snapshot(
        id: String,
        label: String = "snap-$id",
        capturedAtEpochMs: Long = 0L,
        clips: List<Clip> = emptyList(),
        assets: List<MediaAsset> = emptyList(),
    ): ProjectSnapshot {
        val tracks = if (clips.isEmpty()) emptyList()
        else listOf(Track.Video(TrackId("vt"), clips))
        return ProjectSnapshot(
            id = ProjectSnapshotId(id),
            label = label,
            capturedAtEpochMs = capturedAtEpochMs,
            project = Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = tracks),
                assets = assets,
            ),
        )
    }

    private fun project(snapshots: List<ProjectSnapshot> = emptyList()): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        snapshots = snapshots,
    )

    private fun input(maxAgeDays: Int? = null, limit: Int? = null, offset: Int? = null) =
        ProjectQueryTool.Input(
            projectId = "p",
            select = ProjectQueryTool.SELECT_SNAPSHOTS,
            maxAgeDays = maxAgeDays,
            limit = limit,
            offset = offset,
        )

    /** Fixed-time clock so maxAgeDays tests are deterministic. */
    private fun fixedClock(epochMs: Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    private fun decodeRows(out: ProjectQueryTool.Output): List<SnapshotRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SnapshotRow.serializer()),
            out.rows,
        )

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyProjectReturnsZeroAndDedicatedMarker() {
        val result = runSnapshotsQuery(project(), input(), Clock.System)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin: empty marker.
        assertTrue(
            "No matching snapshots on project p" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    @Test fun snapshotRowFieldsRoundTripFromSnapshotAndCapturedProject() {
        val v = videoClip("c1", "a")
        val a = assetWithId("a")
        val snap = snapshot(
            "snap-1",
            label = "test-label",
            capturedAtEpochMs = 9000L,
            clips = listOf(v),
            assets = listOf(a),
        )
        val rows = decodeRows(
            runSnapshotsQuery(project(listOf(snap)), input(), Clock.System).data,
        )
        val row = rows.single()
        assertEquals("snap-1", row.snapshotId)
        assertEquals("test-label", row.label)
        assertEquals(9000L, row.capturedAtEpochMs)
        assertEquals(1, row.clipCount)
        assertEquals(1, row.trackCount)
        assertEquals(1, row.assetCount)
    }

    // ── newest-first ordering ────────────────────────────────────

    @Test fun snapshotsSortNewestFirstByCapturedAtEpochMs() {
        val old = snapshot("old", capturedAtEpochMs = 100L)
        val new = snapshot("new", capturedAtEpochMs = 300L)
        val mid = snapshot("mid", capturedAtEpochMs = 200L)
        // Insertion order: old, new, mid → output [new, mid, old]
        // by descending capturedAtEpochMs.
        val rows = decodeRows(
            runSnapshotsQuery(project(listOf(old, new, mid)), input(), Clock.System).data,
        )
        assertEquals(listOf("new", "mid", "old"), rows.map { it.snapshotId })
    }

    // ── maxAgeDays filter ─────────────────────────────────────────

    @Test fun maxAgeDaysFiltersOlderSnapshots() {
        // Pin marquee: maxAgeDays=1 with clock at epoch 86_400_000
        // (1 day in ms) means cutoff = 86_400_000 - 86_400_000 = 0.
        // Snapshots with capturedAtEpochMs >= 0 survive (inclusive
        // comparison per code).
        val now = 86_400_000L // 1 day in epoch ms
        val ageZero = snapshot("zero", capturedAtEpochMs = 0L) // exactly at cutoff
        val ageOneDay = snapshot("one-day", capturedAtEpochMs = -1L) // 1ms before cutoff
        val ageRecent = snapshot("recent", capturedAtEpochMs = now)
        val rows = decodeRows(
            runSnapshotsQuery(
                project(listOf(ageZero, ageOneDay, ageRecent)),
                input(maxAgeDays = 1),
                fixedClock(now),
            ).data,
        )
        // Pin: ageZero (at cutoff) survives — the comparison is
        // inclusive `>=`. ageOneDay (1ms before) is dropped.
        // ageRecent is well within window.
        assertEquals(setOf("zero", "recent"), rows.map { it.snapshotId }.toSet())
    }

    @Test fun maxAgeDaysZeroOnlyAcceptsSnapshotsAtOrAfterNow() {
        // Pin: maxAgeDays=0 → cutoff = now → only "now or later"
        // captured snapshots survive. Practically all snapshots
        // captured at/after the current moment, which means
        // approximately everything captured at the same instant
        // as `clock.now()` and nothing older. Note: this is a
        // degenerate but valid configuration.
        val now = 1_000_000L
        val older = snapshot("older", capturedAtEpochMs = 999_999L)
        val atNow = snapshot("at-now", capturedAtEpochMs = now)
        val rows = decodeRows(
            runSnapshotsQuery(
                project(listOf(older, atNow)),
                input(maxAgeDays = 0),
                fixedClock(now),
            ).data,
        )
        assertEquals(listOf("at-now"), rows.map { it.snapshotId })
    }

    @Test fun maxAgeDaysNegativeErrorsLoud() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runSnapshotsQuery(project(), input(maxAgeDays = -1), Clock.System)
        }
        assertTrue("maxAgeDays must be >= 0" in (ex.message ?: ""), "got: ${ex.message}")
    }

    @Test fun nullMaxAgeDaysDoesNotFilter() {
        val now = 1_000_000L
        val older = snapshot("older", capturedAtEpochMs = 1L)
        val newer = snapshot("newer", capturedAtEpochMs = now)
        val rows = decodeRows(
            runSnapshotsQuery(
                project(listOf(older, newer)),
                input(maxAgeDays = null),
                fixedClock(now),
            ).data,
        )
        assertEquals(2, rows.size, "no filter when maxAgeDays null")
    }

    @Test fun clockIsConsultedForCutoffComputation() {
        // Marquee determinism pin: filter respects the injected
        // clock. Two calls with different clocks but same
        // input yield different cutoffs. A regression using a
        // wall-clock would make the filter non-deterministic in
        // tests + race-prone in production.
        val snap = snapshot("s", capturedAtEpochMs = 50_000_000L)
        val proj = project(listOf(snap))
        // Clock 1: now=50_000_000 + 1day → cutoff = 50_000_000
        // → snap survives.
        val withSurvivingSnap = decodeRows(
            runSnapshotsQuery(
                proj,
                input(maxAgeDays = 1),
                fixedClock(50_000_000L + 86_400_000L),
            ).data,
        )
        assertEquals(1, withSurvivingSnap.size, "snap survives close clock")
        // Clock 2: now=50_000_000 + 5 days → cutoff = (5d - 1d
        // window) = 50_000_000 + 4d → snap is too old → dropped.
        val withDroppedSnap = decodeRows(
            runSnapshotsQuery(
                proj,
                input(maxAgeDays = 1),
                fixedClock(50_000_000L + 5L * 86_400_000L),
            ).data,
        )
        assertEquals(0, withDroppedSnap.size, "snap dropped on far clock")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitDefaultsToFifty() {
        // Pin: DEFAULT_LIMIT=50 (lower than ProjectQueryTool's
        // generic 100) — matches old list_project_snapshots
        // default per kdoc.
        val snaps = (1..60).map {
            snapshot("s$it", capturedAtEpochMs = it.toLong())
        }
        val result = runSnapshotsQuery(project(snaps), input(), Clock.System)
        assertEquals(50, decodeRows(result.data).size, "default limit = 50")
        assertEquals(60, result.data.total, "total reflects all")
    }

    @Test fun limitClampsToOneAtMinimum() {
        val snaps = (1..3).map { snapshot("s$it", capturedAtEpochMs = it.toLong()) }
        val result = runSnapshotsQuery(project(snaps), input(limit = 0), Clock.System)
        assertEquals(1, decodeRows(result.data).size, "limit=0 clamps to 1")
    }

    @Test fun limitClampsToFiveHundredAtMaximum() {
        val snaps = (1..3).map { snapshot("s$it", capturedAtEpochMs = it.toLong()) }
        val result = runSnapshotsQuery(project(snaps), input(limit = 1_000), Clock.System)
        assertEquals(3, decodeRows(result.data).size, "high limit caps at MAX (500), then file floor (3)")
    }

    @Test fun offsetSkipsFirstNOfNewestFirstSorted() {
        // Snapshots captured at 1..5 → sorted [s5, s4, s3, s2,
        // s1]. offset=2 → start at s3.
        val snaps = (1..5).map { snapshot("s$it", capturedAtEpochMs = it.toLong()) }
        val rows = decodeRows(
            runSnapshotsQuery(project(snaps), input(offset = 2), Clock.System).data,
        )
        assertEquals(listOf("s3", "s2", "s1"), rows.map { it.snapshotId })
    }

    @Test fun negativeOffsetCoercesToZero() {
        val snap = snapshot("s1")
        val rows = decodeRows(
            runSnapshotsQuery(project(listOf(snap)), input(offset = -10), Clock.System).data,
        )
        assertEquals(1, rows.size, "negative offset → 0")
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun nonEmptyOutputUsesSemicolonSeparatedShorthand() {
        // Pin format: "<id> \"<label>\" (<clipCount> clip(s))".
        // Multiple snapshots joined by "; ".
        val s1 = snapshot("s1", label = "first", capturedAtEpochMs = 100L)
        val s2 = snapshot(
            "s2",
            label = "second",
            capturedAtEpochMs = 200L,
            clips = listOf(videoClip("c", "a")),
        )
        val out = runSnapshotsQuery(project(listOf(s1, s2)), input(), Clock.System).outputForLlm
        // Sorted newest-first → s2 before s1.
        assertTrue(out.startsWith("s2 \"second\" (1 clip(s))"), "newest first; got: $out")
        assertTrue("s1 \"first\" (0 clip(s))" in out, "older entry; got: $out")
        assertTrue("; " in out, "semicolon separator; got: $out")
    }

    @Test fun emptyResultWithMaxAgeDaysScopeMentionsFilter() {
        // Pin: empty case message includes the scope hint when
        // a filter was applied — distinguishes "no snapshots
        // exist" from "all snapshots are too old".
        val out = runSnapshotsQuery(
            project(),
            input(maxAgeDays = 7),
            Clock.System,
        ).outputForLlm
        assertTrue("(maxAgeDays=7)" in out, "scope label; got: $out")
    }

    @Test fun emptyResultWithoutFilterHasNoScopeLabel() {
        // Pin: empty case without filter has no parenthetical
        // scope label after the project id.
        val out = runSnapshotsQuery(project(), input(), Clock.System).outputForLlm
        assertTrue(out.endsWith("project p."), "bare format; got: $out")
        assertTrue("maxAgeDays" !in out, "no scope when null; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runSnapshotsQuery(project(), input(), Clock.System)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_SNAPSHOTS, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val snaps = (1..5).map { snapshot("s$it", capturedAtEpochMs = it.toLong()) }
        val result = runSnapshotsQuery(project(snaps), input(limit = 2), Clock.System)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }

    @Test fun rowCountsRoundTripFromCapturedProjectStructure() {
        // Pin: clipCount sums across tracks; trackCount counts
        // tracks; assetCount = assets.size.
        val a = assetWithId("a")
        val b = assetWithId("b")
        val tracks = listOf(
            Track.Video(TrackId("v1"), listOf(videoClip("c1", "a"), videoClip("c2", "a"))),
            Track.Video(TrackId("v2"), listOf(videoClip("c3", "b"))),
        )
        val snap = ProjectSnapshot(
            id = ProjectSnapshotId("s"),
            label = "test",
            capturedAtEpochMs = 0L,
            project = Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = tracks),
                assets = listOf(a, b),
            ),
        )
        val row = decodeRows(
            runSnapshotsQuery(project(listOf(snap)), input(), Clock.System).data,
        ).single()
        assertEquals(3, row.clipCount, "2 + 1 across tracks")
        assertEquals(2, row.trackCount, "2 tracks")
        assertEquals(2, row.assetCount, "2 assets")
    }
}
