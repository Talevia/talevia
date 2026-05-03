package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.diff.TIMELINE_DIFF_MAX_DETAIL
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runTimelineDiffQuery] —
 * `project_query(select=timeline_diff)`. Single-row
 * timeline-only diff between two payloads of the same
 * project, used to answer "what did my timeline change
 * between v1 and v2?" without source / lockfile noise.
 * Cycle 141 audit: 126 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **At least one of `fromSnapshotId` / `toSnapshotId` is
 *    required.** Both null → `require()` fails: "diffing
 *    current-vs-current is always identical and almost
 *    always a usage error." The guard names the SELECT and
 *    explains why so the LLM self-corrects without consulting
 *    docs. A regression silently allowing both-null would
 *    return a meaningless identical-diff every time.
 *
 * 2. **`resolveSide` looks up snapshots by id with a
 *    discoverable error path.** Unknown snapshot id → error
 *    naming the offending id, the project, the side, and
 *    pointing at `project_query(select=snapshots)` for
 *    discovery. The label format is `"<projectId> @current"`
 *    when null, `"<projectId> @<snap.label>"` when found.
 *
 * 3. **Detail lists capped at [TIMELINE_DIFF_MAX_DETAIL]
 *    while `totalChanges` stays exact.** A wholesale rewrite
 *    that adds 80 clips can't blow the response into
 *    thousands of tokens — caps at 50 — but the count
 *    surface stays accurate so the LLM knows to pull a
 *    different view if it needs the full set. Pinned by
 *    constructing a from/to pair that exceeds the cap.
 */
class TimelineDiffQueryTest {

    private val timeRange = TimeRange(start = Duration.ZERO, duration = 1.seconds)

    private fun videoClip(id: String) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId("a-$id"),
    )

    private fun timelineWith(clipIds: List<String>): Timeline =
        Timeline(tracks = listOf(Track.Video(TrackId("vt"), clipIds.map(::videoClip))))

    private fun project(
        id: String = "p",
        currentTimeline: Timeline = Timeline(),
        snapshots: List<ProjectSnapshot> = emptyList(),
    ): Project = Project(
        id = ProjectId(id),
        timeline = currentTimeline,
        snapshots = snapshots,
    )

    private fun snapshot(
        id: String,
        label: String = id,
        timeline: Timeline = Timeline(),
        capturedAtEpochMs: Long = 1_000L,
    ): ProjectSnapshot = ProjectSnapshot(
        id = ProjectSnapshotId(id),
        label = label,
        capturedAtEpochMs = capturedAtEpochMs,
        // Inner project IDs don't matter for diff math; the diff
        // walks the inner timeline only.
        project = Project(id = ProjectId("inner"), timeline = timeline),
    )

    private fun input(
        from: String? = null,
        to: String? = null,
    ) = ProjectQueryTool.Input(
        select = "timeline_diff",
        fromSnapshotId = from,
        toSnapshotId = to,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): TimelineDiffRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(TimelineDiffRow.serializer()),
            out.rows,
        ).single()

    // ── input require ─────────────────────────────────────────

    @Test fun bothSnapshotIdsNullFailsLoudWithExplanation() {
        // The marquee require() pin: current-vs-current is
        // always identical and almost always a usage error.
        val ex = assertFailsWith<IllegalArgumentException> {
            runTimelineDiffQuery(project(), input())
        }
        val msg = ex.message.orEmpty()
        assertTrue("timeline_diff" in msg, "select named in error; got: $msg")
        assertTrue("fromSnapshotId" in msg, "field hint surfaced; got: $msg")
        assertTrue("toSnapshotId" in msg, "field hint surfaced; got: $msg")
        assertTrue(
            "current-vs-current" in msg,
            "rationale surfaced; got: $msg",
        )
    }

    @Test fun onlyFromSnapshotPresentIsAccepted() {
        val snap = snapshot("s1", timeline = timelineWith(listOf("c1")))
        val proj = project(
            currentTimeline = timelineWith(listOf("c1", "c2")),
            snapshots = listOf(snap),
        )
        // Snapshot has 1 clip, current has 2 → 1 added.
        val row = decodeRow(runTimelineDiffQuery(proj, input(from = "s1")).data)
        assertEquals(1, row.clipsAdded.size)
        assertEquals(false, row.identical)
    }

    @Test fun onlyToSnapshotPresentIsAccepted() {
        val snap = snapshot("s1", timeline = timelineWith(listOf("c1", "c2")))
        val proj = project(
            currentTimeline = timelineWith(listOf("c1")),
            snapshots = listOf(snap),
        )
        // Current has 1 clip, snapshot has 2 → 1 added (going
        // from current to snapshot).
        val row = decodeRow(runTimelineDiffQuery(proj, input(to = "s1")).data)
        assertEquals(1, row.clipsAdded.size)
    }

    // ── resolveSide ──────────────────────────────────────────

    @Test fun unknownFromSnapshotErrorsWithDiscoverableHint() {
        val proj = project()
        val ex = assertFailsWith<IllegalStateException> {
            runTimelineDiffQuery(proj, input(from = "ghost"))
        }
        val msg = ex.message.orEmpty()
        assertTrue("ghost" in msg, "offending id named; got: $msg")
        assertTrue("p" in msg, "project named; got: $msg")
        assertTrue("from side" in msg, "side named; got: $msg")
        assertTrue(
            "project_query(select=snapshots)" in msg,
            "discovery hint included; got: $msg",
        )
    }

    @Test fun unknownToSnapshotErrorsWithSideField() {
        // Pin: side label flips to "to side" when toSnapshotId
        // is the broken one — distinguishes which arg the LLM
        // typoed when both are present.
        val knownSnap = snapshot("known")
        val proj = project(snapshots = listOf(knownSnap))
        val ex = assertFailsWith<IllegalStateException> {
            runTimelineDiffQuery(proj, input(from = "known", to = "ghost"))
        }
        assertTrue("to side" in ex.message.orEmpty(), "got: ${ex.message}")
    }

    @Test fun labelFormatCurrentVsSnapshotByLabel() {
        // Pin: null id → "<projectId> @current"; resolved
        // snapshot → "<projectId> @<snap.label>". Note: uses
        // snap.label NOT snap.id — labels are human-friendly.
        val snap = snapshot("s1-id", label = "v1-launch")
        val proj = project(snapshots = listOf(snap))
        val row = decodeRow(runTimelineDiffQuery(proj, input(from = "s1-id")).data)
        assertEquals("p @v1-launch", row.fromLabel, "uses snap.label")
        assertEquals("p @current", row.toLabel, "null → @current")
    }

    @Test fun labelFormatBothSnapshotsResolvedByTheirOwnLabels() {
        val a = snapshot("s-a", label = "alpha")
        val b = snapshot("s-b", label = "beta")
        val proj = project(snapshots = listOf(a, b))
        val row = decodeRow(
            runTimelineDiffQuery(proj, input(from = "s-a", to = "s-b")).data,
        )
        assertEquals("p @alpha", row.fromLabel)
        assertEquals("p @beta", row.toLabel)
    }

    // ── identical flag ↔ totalChanges ──────────────────────────

    @Test fun identicalTimelineSetsFlagAndZeroCount() {
        // Pin: identical = (totalChanges == 0). Both labels
        // surface the timelines as the same.
        val snap = snapshot("s1", timeline = timelineWith(listOf("c1")))
        val proj = project(
            currentTimeline = timelineWith(listOf("c1")),
            snapshots = listOf(snap),
        )
        val result = runTimelineDiffQuery(proj, input(from = "s1"))
        val row = decodeRow(result.data)
        assertEquals(true, row.identical)
        assertEquals(0, row.totalChanges)
        assertEquals(0, row.clipsAdded.size)
        assertEquals(0, row.clipsRemoved.size)
        // Pin: identical-case narrative format.
        assertTrue(
            "timeline identical" in result.outputForLlm,
            "identical narrative; got: ${result.outputForLlm}",
        )
        assertTrue(
            "no tracks/clips added, removed, or changed" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    @Test fun nonIdenticalNarrativeShowsDeltaCounts() {
        val snap = snapshot("s1", timeline = timelineWith(listOf("c1", "c2")))
        val proj = project(
            currentTimeline = timelineWith(listOf("c1", "c3", "c4")),
            snapshots = listOf(snap),
        )
        // s1 → current: c2 removed, c3+c4 added.
        val out = runTimelineDiffQuery(proj, input(from = "s1")).outputForLlm
        // Pin: format "X → Y: timeline NΔ (+aclip / -bclip / ~cclip)".
        assertTrue("p @s1 → p @current" in out, "labels in narrative; got: $out")
        assertTrue("3Δ" in out, "totalChanges in delta; got: $out")
        assertTrue("+2clip" in out, "added count; got: $out")
        assertTrue("-1clip" in out, "removed count; got: $out")
        assertTrue("~0clip" in out, "changed count; got: $out")
    }

    @Test fun trackMentionDroppedWhenZeroTrackChanges() {
        // Pin: ", ±N track" segment ONLY when tracksAdded OR
        // tracksRemoved non-empty. Pure clip diff omits it.
        val snap = snapshot("s1", timeline = timelineWith(listOf("c1")))
        val proj = project(
            currentTimeline = timelineWith(listOf("c1", "c2")),
            snapshots = listOf(snap),
        )
        val out = runTimelineDiffQuery(proj, input(from = "s1")).outputForLlm
        assertTrue(
            "track" !in out.substringAfter("clip"),
            "track segment absent when no track delta; got: $out",
        )
    }

    @Test fun trackMentionAppearsWhenTracksAddedOrRemoved() {
        val snap = snapshot(
            "s1",
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt")))),
        )
        // Current adds an audio track.
        val proj = project(
            currentTimeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt")),
                    Track.Audio(TrackId("at")),
                ),
            ),
            snapshots = listOf(snap),
        )
        val out = runTimelineDiffQuery(proj, input(from = "s1")).outputForLlm
        assertTrue("±1 track" in out, "track segment surfaces; got: $out")
    }

    // ── detail cap (50) vs exact totalChanges ────────────────

    @Test fun detailListsCappedAtFiftyButTotalChangesExact() {
        // Pin: cap at TIMELINE_DIFF_MAX_DETAIL (=50). 80 added
        // clips → row.clipsAdded.size = 50, but totalChanges
        // = 80. The narrative '+80clip' surfaces the exact
        // count even though the row only carries 50.
        assertEquals(50, TIMELINE_DIFF_MAX_DETAIL, "cap pinned at 50")
        // Both timelines carry the same `vt` track so the
        // diff isolates clip-add deltas (no track-level
        // change polluting the count).
        val snap = snapshot(
            "s1",
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt")))),
        )
        val current = timelineWith((1..80).map { "c$it" })
        val proj = project(
            currentTimeline = current,
            snapshots = listOf(snap),
        )
        val result = runTimelineDiffQuery(proj, input(from = "s1"))
        val row = decodeRow(result.data)
        assertEquals(50, row.clipsAdded.size, "detail capped")
        assertEquals(80, row.totalChanges, "totalChanges remains exact")
        assertTrue(
            "80Δ" in result.outputForLlm,
            "narrative surfaces exact total; got: ${result.outputForLlm}",
        )
        // Pin: narrative shows the row.clipsAdded.size (50),
        // NOT the exact added count, because narrative reads
        // from `diff.clipsAdded.size`. This documents the
        // observed semantic: the delta header is exact but
        // the per-bucket size mirrors the capped list.
        assertTrue(
            "+50clip" in result.outputForLlm,
            "per-bucket size mirrors capped list; got: ${result.outputForLlm}",
        )
    }

    // ── change vs add/remove discrimination ──────────────────

    @Test fun clipChangedSurfacesAsClipsChangedNotAddedRemoved() {
        // Pin: same clipId in both timelines but with content
        // delta → routed to clipsChanged, NOT clipsAdded /
        // clipsRemoved. Distinguishes "edit" from
        // "delete+insert" so the LLM doesn't double-count.
        val fromTimeline = timelineWith(listOf("c1"))
        // To: same clip id, longer duration → counts as changed.
        val changedClip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(start = Duration.ZERO, duration = 5.seconds),
            sourceRange = timeRange,
            assetId = AssetId("a-c1"),
        )
        val toTimeline = Timeline(
            tracks = listOf(Track.Video(TrackId("vt"), listOf(changedClip))),
        )
        val snap = snapshot("s1", timeline = fromTimeline)
        val proj = project(currentTimeline = toTimeline, snapshots = listOf(snap))
        val row = decodeRow(runTimelineDiffQuery(proj, input(from = "s1")).data)
        assertEquals(0, row.clipsAdded.size)
        assertEquals(0, row.clipsRemoved.size)
        assertEquals(1, row.clipsChanged.size, "edit routes to changed bucket")
        assertEquals("c1", row.clipsChanged.single().clipId)
    }

    // ── output framing ───────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRow() {
        val snap = snapshot("s1")
        val proj = project(snapshots = listOf(snap))
        val result = runTimelineDiffQuery(proj, input(from = "s1"))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_TIMELINE_DIFF, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleEchoesProjectIdAndBothLabels() {
        val snap = snapshot("s1", label = "alpha")
        val proj = project(id = "myproj", snapshots = listOf(snap))
        val result = runTimelineDiffQuery(proj, input(from = "s1"))
        assertEquals(
            "project_query timeline_diff myproj (myproj @alpha → myproj @current)",
            result.title,
        )
    }
}
