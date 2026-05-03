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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runRenderStaleQuery] — `project_query(select=
 * render_stale)`. Lists video clips whose per-clip mezzanine
 * cache doesn't match at the project's OutputProfile + engine.
 * Cycle 131 audit: 111 LOC, 2 transitive test refs (covered only
 * via integration through ProjectQueryTool dispatcher); the
 * staleness-vs-eligibility distinction was previously
 * unprotected at the unit level.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Empty result has TWO disambiguating meanings the kdoc
 *    explicitly calls out.** Per kdoc: "Empty list has two
 *    meanings the caller disambiguates via timeline shape:
 *    eligible shape + zero return = full reuse possible; non-
 *    eligible shape ... = per-clip cache doesn't apply." The
 *    summary text is the LLM's only signal for which case it's
 *    in. A regression collapsing the message to a generic "0
 *    stale" would leave the LLM unable to tell "everything's
 *    cached" from "this query doesn't apply to this timeline".
 *
 * 2. **Engine ID flows from input → DEFAULT_PER_CLIP_ENGINE_ID
 *    → fingerprint computation.** Per code:
 *    `input.engineId ?: DEFAULT_PER_CLIP_ENGINE_ID` (= "ffmpeg-
 *    jvm"). The summary surfaces `engine=<id>` so operators
 *    know which platform's render cache was consulted. A
 *    regression silently changing the default would
 *    misattribute cache-misses across engines.
 *
 * 3. **Sort by clipId ASC for reproducible paging.** Per kdoc:
 *    "Sorted by clipId ASC for reproducible paging." A
 *    regression dropping the sort would cause re-runs of the
 *    same query to surface different rows under pagination.
 */
class RenderStaleQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
    )

    /**
     * Build a project with a SINGLE Video track (eligible for
     * per-clip cache per `renderStaleClips`'s contract).
     */
    private fun eligibleProject(clips: List<Clip>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(
            tracks = if (clips.isEmpty()) emptyList()
            else listOf(Track.Video(TrackId("vt"), clips)),
        ),
    )

    /**
     * Build a project with TWO Video tracks (ineligible per
     * `renderStaleClips` — videoTracks.size != 1 → returns empty
     * regardless of cache state).
     */
    private fun ineligibleProject(clips: List<Clip>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(
            tracks = listOf(
                Track.Video(TrackId("vt1"), clips),
                Track.Video(TrackId("vt2"), emptyList()),
            ),
        ),
    )

    private fun input(engineId: String? = null) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_RENDER_STALE,
        engineId = engineId,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<RenderStaleClipReportRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(RenderStaleClipReportRow.serializer()),
            out.rows,
        )

    // ── empty / shape paths ───────────────────────────────────────

    @Test fun emptyProjectReturnsZeroAndAllCachedSummary() {
        // Empty timeline → eligibility check fails (zero video
        // tracks → renderStaleClips returns []). Per kdoc, this
        // is the "non-eligible shape" branch but with 0 total
        // clips, the summary uses the unified "all eligible
        // cached" phrase.
        val result = runRenderStaleQuery(eligibleProject(emptyList()), input(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin: summary mentions "All eligible Video clips have
        // cached mezzanines" + the total clip count + the
        // engineId for full LLM context.
        val out = result.outputForLlm
        assertTrue("All eligible Video clips" in out, "got: $out")
        assertTrue("engine=ffmpeg-jvm" in out, "default engine; got: $out")
        assertTrue("0 clip(s) on the timeline" in out, "total clips; got: $out")
    }

    @Test fun ineligibleTimelineReturnsZeroEvenWithUncachedClips() {
        // Marquee semantic pin: multi-video-track timeline is
        // ineligible per renderStaleClips. Even with zero render
        // cache entries, the query returns 0 stale (the per-clip
        // cache doesn't apply to this shape). The LLM disambig-
        // uates via the kdoc-promised "0 of N clip(s) on the
        // timeline" wording vs the explicit "All eligible"
        // phrasing — but in either case total=0.
        val clip = videoClip("c1", "asset-1")
        val result = runRenderStaleQuery(ineligibleProject(listOf(clip)), input(), 100, 0)
        assertEquals(0, result.data.total)
    }

    // ── eligible timeline + uncached clips → all stale ───────────

    @Test fun singleVideoTrackWithoutCacheReportsAllClipsAsStale() {
        // Pin: eligible shape (single video track, all video
        // clips) + no render cache → every clip surfaces as
        // stale.
        val clips = (1..3).map { videoClip("c$it", "asset-$it") }
        val result = runRenderStaleQuery(eligibleProject(clips), input(), 100, 0)
        val rows = decodeRows(result.data)
        assertEquals(3, result.data.total)
        assertEquals(3, rows.size)
        // Every clip surfaces with a fingerprint.
        for (row in rows) {
            assertTrue(row.fingerprint.isNotBlank(), "fingerprint computed; got row=$row")
        }
    }

    // ── sort by clipId ASC ────────────────────────────────────────

    @Test fun rowsAreSortedAlphabeticallyByClipId() {
        // Pin marquee: insertion order [zebra, alpha, mango] →
        // output [alpha, mango, zebra]. UI consumers and
        // pagination depend on this stability.
        val z = videoClip("zebra", "az")
        val a = videoClip("alpha", "aa")
        val m = videoClip("mango", "am")
        val rows = decodeRows(
            runRenderStaleQuery(eligibleProject(listOf(z, a, m)), input(), 100, 0).data,
        )
        assertEquals(listOf("alpha", "mango", "zebra"), rows.map { it.clipId })
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsRowsButTotalReflectsAllStaleClips() {
        val clips = (1..5).map { videoClip("c${it.toString().padStart(2, '0')}", "a$it") }
        val result = runRenderStaleQuery(eligibleProject(clips), input(), 2, 0)
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total, "total reflects all")
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() {
        val clips = (1..5).map { videoClip("c${it.toString().padStart(2, '0')}", "a$it") }
        val result = runRenderStaleQuery(eligibleProject(clips), input(), 100, 2)
        val rows = decodeRows(result.data)
        // Sorted: c01..c05; offset=2 → start at c03.
        assertEquals(3, rows.size)
        assertEquals("c03", rows[0].clipId)
    }

    // ── engineId default + override ──────────────────────────────

    @Test fun summaryDefaultsEngineToFfmpegJvmWhenNotSet() {
        val clip = videoClip("c1", "a")
        val out = runRenderStaleQuery(eligibleProject(listOf(clip)), input(), 100, 0).outputForLlm
        assertTrue("engine=ffmpeg-jvm" in out, "default engine surfaces; got: $out")
    }

    @Test fun explicitEngineIdFlowsToSummary() {
        val clip = videoClip("c1", "a")
        val out = runRenderStaleQuery(
            eligibleProject(listOf(clip)),
            input(engineId = "media3-android"),
            100,
            0,
        ).outputForLlm
        assertTrue(
            "engine=media3-android" in out,
            "explicit engine surfaces; got: $out",
        )
    }

    @Test fun explicitEngineIdSurfacesEvenInEmptyResultMessage() {
        // Pin: the engine id appears in BOTH branches of the
        // summary (empty + non-empty) so the LLM always knows
        // which platform was queried.
        val out = runRenderStaleQuery(
            eligibleProject(emptyList()),
            input(engineId = "avfoundation-ios"),
            100,
            0,
        ).outputForLlm
        assertTrue("engine=avfoundation-ios" in out, "got: $out")
    }

    // ── outputForLlm summary text ─────────────────────────────────

    @Test fun nonEmptyOutputShowsCountAndPreviewWithFingerprints() {
        val clips = (1..3).map { videoClip("c$it", "a$it") }
        val out = runRenderStaleQuery(eligibleProject(clips), input(), 100, 0).outputForLlm
        // Pin format: "<total> of <totalClips> clip(s) render-
        // stale on engine=<id>. <preview-list>".
        assertTrue("3 of 3 clip(s) render-stale" in out, "count + total; got: $out")
        // Each preview entry: "clipId (fp=<fingerprint>)".
        for (i in 1..3) {
            assertTrue("c$i (fp=" in out, "preview entry for c$i; got: $out")
        }
    }

    @Test fun nonEmptyOutputShowsTrunctionNoteWhenLimitTrims() {
        // Pin: "(showing N of M — raise limit to see more)" hint
        // when total > page.size.
        val clips = (1..5).map { videoClip("c${it.toString().padStart(2, '0')}", "a$it") }
        val out = runRenderStaleQuery(eligibleProject(clips), input(), 2, 0).outputForLlm
        assertTrue("(showing 2 of 5" in out, "trunc note; got: $out")
        assertTrue("raise limit to see more" in out, "recovery hint; got: $out")
    }

    @Test fun nonEmptyOutputShowsEllipsisWhenPageHasMoreThanFive() {
        // Pin: page.take(5) for the preview head; if page.size >
        // 5, append "; …" suffix.
        val clips = (1..7).map { videoClip("c${it.toString().padStart(2, '0')}", "a$it") }
        val out = runRenderStaleQuery(eligibleProject(clips), input(), 100, 0).outputForLlm
        assertTrue(out.endsWith("; …"), "ellipsis when page > 5; got: $out")
    }

    @Test fun nonEmptyOutputNoEllipsisWhenPageAtMostFive() {
        val clips = (1..3).map { videoClip("c$it", "a$it") }
        val out = runRenderStaleQuery(eligibleProject(clips), input(), 100, 0).outputForLlm
        assertTrue("…" !in out, "no ellipsis when page ≤ 5; got: $out")
    }

    @Test fun emptyResultSummaryMentionsTotalClipCount() {
        // Pin: even when nothing is stale, the summary names the
        // total-clips count so the LLM can sanity-check
        // "everything's cached" against the actual project size.
        val clips = (1..3).map { videoClip("c$it", "a$it") }
        // Use ineligible timeline → result empty despite N clips.
        val out = runRenderStaleQuery(ineligibleProject(clips), input(), 100, 0).outputForLlm
        assertTrue(
            "3 clip(s) on the timeline" in out,
            "total clips in empty branch; got: $out",
        )
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runRenderStaleQuery(eligibleProject(emptyList()), input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_RENDER_STALE, result.data.select)
    }

    @Test fun titleIncludesTotalCount() {
        val clips = (1..5).map { videoClip("c${it.toString().padStart(2, '0')}", "a$it") }
        val result = runRenderStaleQuery(eligibleProject(clips), input(), 100, 0)
        assertTrue(
            "render_stale (5)" in (result.title ?: ""),
            "title includes total; got: ${result.title}",
        )
    }

    // ── row shape ─────────────────────────────────────────────────

    @Test fun rowCarriesClipIdAndFingerprintFields() {
        val clip = videoClip("c1", "asset-1")
        val rows = decodeRows(
            runRenderStaleQuery(eligibleProject(listOf(clip)), input(), 100, 0).data,
        )
        val row = rows.single()
        assertEquals("c1", row.clipId)
        assertTrue(row.fingerprint.isNotBlank(), "fingerprint computed; got: $row")
    }
}
