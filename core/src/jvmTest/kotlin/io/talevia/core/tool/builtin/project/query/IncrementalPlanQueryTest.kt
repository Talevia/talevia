package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runIncrementalPlanQuery] —
 * `project_query(select=incremental_plan)`. The M5 §3.2 capstone:
 * "if I edit these source nodes, what work does the next export
 * need?" Cycle 122 audit: 79 LOC, **zero** transitive test
 * references; the 3-bucket pre-export answer was previously
 * unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`changedSources` empty / no-affected-clips → all 3 buckets
 *    empty.** Per kdoc IncrementalPlan: "Empty `sourceNodeIds`
 *    (or no clips bound to any of them) → all three buckets
 *    empty (`workCount=0`)." Two distinct paths to the same
 *    empty result. `noChangedSourcesProducesEmptyPlan` and
 *    `unrelatedChangedSourcesWithNoBoundClipsProduceEmptyPlan`
 *    pin both.
 *
 * 2. **`workCount = reAigc + onlyRender` (NOT unchanged).** A
 *    regression including `unchanged` in workCount would
 *    silently inflate "how much work?" answers — the LLM uses
 *    workCount to decide whether to advise the user "this edit
 *    is cheap" vs. "expensive, batch it". Pin verified through
 *    `unchangedClipsDoNotCountTowardWorkCount`.
 *
 * 3. **`engineId` defaults to ffmpeg-jvm; explicit override
 *    flows to the summary.** Per `DEFAULT_PER_CLIP_ENGINE_ID`
 *    constant (RenderStaleQuery.kt). The summary text shows
 *    `engine=<id>` so the LLM can reason about which platform's
 *    render-stale set was consulted.
 */
class IncrementalPlanQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId("asset-$id"),
        sourceBinding = binding,
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes, revision = 5),
        )
    }

    /**
     * Build a project with TWO Video tracks so the timeline is
     * ineligible for per-clip cache (`videoTracks.size != 1` →
     * renderStaleClips returns empty). Used to drive clips into
     * the `unchanged` bucket (no aigc-stale + no render-stale =
     * unchanged) without setting up complex lockfile fixtures.
     */
    private fun ineligibleProject(
        nodes: List<SourceNode>,
        clips: List<Clip>,
    ): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(
            tracks = listOf(
                Track.Video(TrackId("vt1"), clips),
                Track.Video(TrackId("vt2"), emptyList()),
            ),
        ),
        source = Source(nodes = nodes, revision = 5),
    )

    private fun input(
        sourceNodeIds: List<String>?,
        engineId: String? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
        sourceNodeIds = sourceNodeIds,
        engineId = engineId,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): IncrementalPlanRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(IncrementalPlanRow.serializer()),
            out.rows,
        ).single()

    // ── empty paths to "no work" ──────────────────────────────────

    @Test fun noChangedSourcesProducesEmptyPlan() {
        // Per IncrementalPlan kdoc: empty sourceNodeIds → all 3
        // buckets empty.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val result = runIncrementalPlanQuery(project(listOf(a), listOf(clip)), input(emptyList()))
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.reAigc)
        assertEquals(emptyList(), row.onlyRender)
        assertEquals(emptyList(), row.unchanged)
        assertEquals(0, row.workCount)
    }

    @Test fun nullSourceNodeIdsBehavesAsEmpty() {
        // Per code: `(input.sourceNodeIds ?: emptyList())`. Null
        // and empty list both produce empty plan. Pin parity.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val result = runIncrementalPlanQuery(project(listOf(a), listOf(clip)), input(null))
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.reAigc)
        assertEquals(emptyList(), row.onlyRender)
        assertEquals(emptyList(), row.unchanged)
    }

    @Test fun unrelatedChangedSourcesWithNoBoundClipsProduceEmptyPlan() {
        // Per IncrementalPlan kdoc: "no clips bound to any of them
        // → all three buckets empty". Distinct from
        // empty-changedSources path: actually walks clipsBoundTo
        // and finds zero affected.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k") // no clip binds a
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k") // no clip binds b
        val unrelatedClip = videoClip("c1", setOf(SourceNodeId("xxx"))) // binds neither
        val result = runIncrementalPlanQuery(
            project(listOf(a, b), listOf(unrelatedClip)),
            input(listOf("a", "b")),
        )
        val row = decodeRow(result.data)
        assertEquals(0, row.workCount)
        assertEquals(emptyList(), row.unchanged, "no clips affected → empty unchanged too")
    }

    // ── happy path: clips affected → unchanged bucket ─────────────

    @Test fun affectedClipInEligibleTimelineWithoutCacheGoesToOnlyRender() {
        // A clip bound to a changed source, with NO lockfile entry
        // and an ELIGIBLE timeline (single video track with video
        // clips), gets render-stale-flagged because no per-clip
        // cache match exists. This is the standard "fresh project,
        // never rendered" path: every changed-binding clip lands
        // in `onlyRender`.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val result = runIncrementalPlanQuery(
            project(listOf(a), listOf(clip)),
            input(listOf("a")),
        )
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.reAigc, "no lockfile → not aigc-stale")
        assertEquals(listOf("c1"), row.onlyRender, "no render cache → render-stale")
        assertEquals(emptyList(), row.unchanged)
        assertEquals(1, row.workCount, "1 onlyRender clip = 1 work unit")
    }

    @Test fun affectedClipInIneligibleTimelineFallsToUnchanged() {
        // Per kdoc: "render-staleness gating is scoped to the
        // per-clip cache eligibility (single Video track timelines
        // per renderStaleClips's contract). For non-eligible
        // shapes, renderStaleClips returns empty — every 'would-be'
        // only-render clip falls through to unchanged."
        // Multi-video-track project triggers the ineligible path.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val result = runIncrementalPlanQuery(
            ineligibleProject(listOf(a), listOf(clip)),
            input(listOf("a")),
        )
        val row = decodeRow(result.data)
        assertEquals(emptyList(), row.reAigc)
        assertEquals(emptyList(), row.onlyRender, "ineligible timeline → render-stale empty")
        assertEquals(listOf("c1"), row.unchanged, "falls through to unchanged")
        assertEquals(0, row.workCount, "unchanged doesn't count")
    }

    @Test fun unchangedClipsDoNotCountTowardWorkCount() {
        // The marquee pin: workCount = reAigc + onlyRender
        // (NOT unchanged). 5 clips in unchanged bucket → workCount=0.
        // Use ineligible timeline to drive clips to the unchanged
        // bucket directly.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clips = (1..5).map { videoClip("c$it", setOf(SourceNodeId("a"))) }
        val row = decodeRow(
            runIncrementalPlanQuery(ineligibleProject(listOf(a), clips), input(listOf("a"))).data,
        )
        assertEquals(5, row.unchanged.size)
        assertEquals(0, row.workCount, "unchanged doesn't count")
    }

    // ── transitive descendant binding ─────────────────────────────

    @Test fun transitiveBindingViaDescendantIncludesClipInAffectedSet() {
        // a → b. Clip binds to b (descendant of a). Editing `a`
        // reaches `c1` via the closure through b. Using the
        // ineligible-timeline shape so the clip lands in
        // `unchanged` (proves the closure walk touches it without
        // confounding render-stale gating).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val clip = videoClip("c1", setOf(SourceNodeId("b")))
        val row = decodeRow(
            runIncrementalPlanQuery(
                ineligibleProject(listOf(a, b), listOf(clip)),
                input(listOf("a")),
            ).data,
        )
        // Clip surfaces in unchanged (closure reached it; ineligible
        // timeline prevents render-stale flagging).
        assertEquals(listOf("c1"), row.unchanged, "transitive binding reaches c1")
    }

    @Test fun multipleChangedSourcesUnionTheirAffectedClips() {
        // Two unrelated source edits each binding their own clips.
        // The plan unions both affected sets. Use ineligible
        // timeline so both clips fall to `unchanged` (cleaner
        // than mixing buckets).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clipA = videoClip("c-a", setOf(SourceNodeId("a")))
        val clipB = videoClip("c-b", setOf(SourceNodeId("b")))
        val row = decodeRow(
            runIncrementalPlanQuery(
                ineligibleProject(listOf(a, b), listOf(clipA, clipB)),
                input(listOf("a", "b")),
            ).data,
        )
        assertEquals(setOf("c-a", "c-b"), row.unchanged.toSet(), "union of affected clips")
    }

    // ── engineId default + override ───────────────────────────────

    @Test fun summaryDefaultsEngineToFfmpegJvmWhenNotSet() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val out = runIncrementalPlanQuery(
            project(listOf(a), listOf(clip)),
            input(listOf("a")),
        ).outputForLlm
        // Pin: default engineId = "ffmpeg-jvm" surfaces in the
        // summary. A regression changing the default would catch.
        assertTrue("engine=ffmpeg-jvm" in out, "default engine; got: $out")
    }

    @Test fun explicitEngineIdFlowsToSummary() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val out = runIncrementalPlanQuery(
            project(listOf(a), listOf(clip)),
            input(listOf("a"), engineId = "media3-android"),
        ).outputForLlm
        assertTrue("engine=media3-android" in out, "explicit engine; got: $out")
    }

    // ── outputForLlm summary text ─────────────────────────────────

    @Test fun emptyPlanSummaryUsesNoWorkPhrase() {
        // isEmpty branch (no work to do) — different summary
        // shape than the non-empty path.
        val out = runIncrementalPlanQuery(
            project(emptyList()),
            input(emptyList()),
        ).outputForLlm
        assertTrue("No work" in out, "no-work marker; got: $out")
        assertTrue("sourceNodeIds=0" in out, "input count surfaces; got: $out")
    }

    @Test fun nonEmptyPlanSummaryShowsBucketBreakdownAndWorkUnits() {
        // Pin format: "engine=<id> | reAigc=<n> onlyRender=<m>
        // unchanged=<k> | total work units=<w>". Eligible
        // timeline → clip lands in onlyRender → workCount=1.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val out = runIncrementalPlanQuery(
            project(listOf(a), listOf(clip)),
            input(listOf("a")),
        ).outputForLlm
        assertTrue("reAigc=0" in out, "reAigc bucket; got: $out")
        assertTrue("onlyRender=1" in out, "onlyRender bucket; got: $out")
        assertTrue("unchanged=0" in out, "unchanged bucket; got: $out")
        assertTrue("total work units=1" in out, "work-unit total; got: $out")
    }

    @Test fun emptyPlanSummaryAfterUnrelatedSourcesShowsZeroBoundClips() {
        // Pin: "0 bound clips" hint when the source ids resolve
        // but reach no clips. Different phrasing than the
        // null-source-edit case so the LLM can distinguish "I
        // forgot to specify edits" from "my edits don't affect
        // anything".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k") // no clips bound
        val out = runIncrementalPlanQuery(project(listOf(a)), input(listOf("a"))).outputForLlm
        assertTrue("No work" in out, "got: $out")
        assertTrue("sourceNodeIds=1" in out, "input count surfaces; got: $out")
        assertTrue("0 bound clips" in out, "zero-clips hint; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelectAndSingleRowFraming() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runIncrementalPlanQuery(project(listOf(a)), input(emptyList()))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_INCREMENTAL_PLAN, result.data.select)
        // Pin: single-row payload — total = returned = 1
        // regardless of bucket sizes. Per kdoc: "the plan is one
        // indivisible answer".
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesWorkUnitCount() {
        // Pin: title format "(N work units)" where N = workCount =
        // reAigc.size + onlyRender.size. Use eligible timeline so
        // 1 clip → 1 work unit (onlyRender).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val result = runIncrementalPlanQuery(
            project(listOf(a), listOf(clip)),
            input(listOf("a")),
        )
        assertTrue("(1 work units)" in (result.title ?: ""), "title; got: ${result.title}")
    }

    @Test fun rowWorkCountFieldMirrorsBucketSums() {
        // Pin redundancy: workCount field on the row mirrors
        // reAigc.size + onlyRender.size. Pre-computed for LLM
        // convenience.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", setOf(SourceNodeId("a")))
        val row = decodeRow(
            runIncrementalPlanQuery(
                project(listOf(a), listOf(clip)),
                input(listOf("a")),
            ).data,
        )
        assertEquals(row.reAigc.size + row.onlyRender.size, row.workCount, "workCount mirrors bucket sums")
    }
}
