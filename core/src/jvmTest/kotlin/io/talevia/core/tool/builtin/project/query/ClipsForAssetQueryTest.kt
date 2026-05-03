package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runClipsForAssetQuery] —
 * `project_query(select=clips_for_asset)`. The "where is this asset
 * used?" reverse lookup. Cycle 118 audit: 81 LOC, **zero**
 * transitive test references; the kdoc commits to "unknown asset id
 * throws so typos surface instead of silently matching nothing"
 * but the silent-typo-prevention contract was unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Unknown asset id throws (NOT empty result).** The kdoc's
 *    marquee design choice: typos surface as errors, not silent
 *    "no matches". A regression returning an empty result on a
 *    typo would let the LLM conclude "asset is unreferenced,
 *    safe to delete" when actually it's still in use under a
 *    correctly-spelled id. `unknownAssetIdThrowsWithRecoveryHint`
 *    pins the error path.
 *
 * 2. **Text clips are skipped (no asset binding).** Per kdoc:
 *    "Text clips never match (no asset)." A regression including
 *    text clips in the asset-bound-clip search would surface
 *    nonsense matches. Only `Clip.Video` and `Clip.Audio` carry
 *    assetId in the domain model.
 *
 * 3. **Within-track sort by `timeRange.start`.** The result is
 *    sorted-by-start within each track so consumers reading a
 *    timeline render know which clip plays first. A regression
 *    dropping the sort would produce nondeterministic order
 *    based on insertion sequence.
 */
class ClipsForAssetQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun assetWithId(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(
            duration = 10.seconds,
            resolution = Resolution(1920, 1080),
            frameRate = FrameRate.FPS_30,
        ),
    )

    private fun videoClip(id: String, assetId: String, start: Duration, duration: Duration) = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId(assetId),
    )

    private fun audioClip(id: String, assetId: String, start: Duration, duration: Duration) = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId(assetId),
    )

    private fun textClip(id: String, start: Duration, duration: Duration) = Clip.Text(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        text = "subtitle",
    )

    private fun project(
        assets: List<MediaAsset>,
        tracks: List<Track>,
    ): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = tracks),
        assets = assets,
    )

    private fun input(assetId: String?) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_CLIPS_FOR_ASSET,
        assetId = assetId,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<ClipForAssetRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ClipForAssetRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingAssetIdErrorsLoud() {
        val result = assertFailsWith<IllegalStateException> {
            runClipsForAssetQuery(project(emptyList(), emptyList()), input(null), 100, 0)
        }
        assertTrue("requires the 'assetId'" in (result.message ?: ""), "got: ${result.message}")
    }

    @Test fun unknownAssetIdThrowsWithRecoveryHint() {
        // The marquee pin. Per kdoc: typos surface instead of
        // silently matching nothing. A regression returning empty
        // would let the LLM mistakenly conclude "asset is
        // unreferenced, safe to delete" when really the typo is
        // the issue. Pin the recovery hint
        // ("project_query(select=assets)") so the LLM can self-
        // recover.
        val ex = assertFailsWith<IllegalArgumentException> {
            runClipsForAssetQuery(project(emptyList(), emptyList()), input("ghost"), 100, 0)
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "must name the queried id; got: $msg")
        assertTrue("not found" in msg, "must say not found; got: $msg")
        assertTrue("project_query(select=assets)" in msg, "recovery hint; got: $msg")
    }

    // ── basic shape: empty / no matches ───────────────────────────

    @Test fun knownAssetWithNoClipsReportsEmptyAndSafeToDeleteHint() {
        // Asset is registered but no clip references it. Pin: NOT
        // an error, but the LLM-facing summary tells the LLM the
        // asset is safe to delete (cleanup workflow signal).
        val a = assetWithId("a")
        val result = runClipsForAssetQuery(project(listOf(a), emptyList()), input("a"), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(
            "unreferenced — safe to delete" in result.outputForLlm,
            "cleanup hint; got: ${result.outputForLlm}",
        )
    }

    // ── happy path: video + audio matches ─────────────────────────

    @Test fun videoClipMatchSurfacesWithVideoKindLabel() {
        val a = assetWithId("a")
        val v = videoClip("c1", "a", Duration.ZERO, 5.seconds)
        val track = Track.Video(TrackId("vt"), listOf(v))
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0)
        val row = decodeRows(result.data).single()
        assertEquals("c1", row.clipId)
        assertEquals("vt", row.trackId)
        assertEquals("video", row.kind)
        assertEquals(0.0, row.startSeconds)
        assertEquals(5.0, row.durationSeconds)
    }

    @Test fun audioClipMatchSurfacesWithAudioKindLabel() {
        val a = assetWithId("a")
        val au = audioClip("c1", "a", 2.seconds, 3.seconds)
        val track = Track.Audio(TrackId("at"), listOf(au))
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0)
        val row = decodeRows(result.data).single()
        assertEquals("audio", row.kind)
        assertEquals(2.0, row.startSeconds)
        assertEquals(3.0, row.durationSeconds)
    }

    // ── text clips are skipped (no asset) ─────────────────────────

    @Test fun textClipsAreSkippedEvenWhenOnSubtitleTrack() {
        // Pin the kdoc commitment: text clips never match, even on
        // a subtitle track that happens to share an id namespace
        // with an asset. A regression including them would surface
        // nonsense rows.
        val a = assetWithId("a")
        val v = videoClip("video-1", "a", Duration.ZERO, 5.seconds)
        val t = textClip("text-1", 5.seconds, 3.seconds)
        val tracks = listOf(
            Track.Video(TrackId("v"), listOf(v)),
            Track.Subtitle(TrackId("s"), listOf(t)),
        )
        val result = runClipsForAssetQuery(project(listOf(a), tracks), input("a"), 100, 0)
        val rows = decodeRows(result.data)
        // Only the video clip surfaces; text clip is filtered out.
        assertEquals(listOf("video-1"), rows.map { it.clipId })
    }

    // ── sorting within a track ────────────────────────────────────

    @Test fun clipsAreSortedByTimeRangeStartWithinEachTrack() {
        // The marquee sort pin. Two clips on the same track,
        // inserted in reverse time order — output MUST be sorted
        // by start.
        val a = assetWithId("a")
        // Insert later-start clip first to verify sort kicks in.
        val later = videoClip("later", "a", 10.seconds, 5.seconds)
        val earlier = videoClip("earlier", "a", 0.seconds, 5.seconds)
        val track = Track.Video(TrackId("vt"), listOf(later, earlier))
        val rows = decodeRows(
            runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0).data,
        )
        assertEquals(listOf("earlier", "later"), rows.map { it.clipId }, "sorted by start time")
    }

    // ── matching across multiple tracks ───────────────────────────

    @Test fun clipsAcrossMultipleTracksAllSurface() {
        val a = assetWithId("a")
        val v = videoClip("v-clip", "a", 1.seconds, 2.seconds)
        val au = audioClip("a-clip", "a", 0.seconds, 5.seconds)
        val tracks = listOf(
            Track.Video(TrackId("vt"), listOf(v)),
            Track.Audio(TrackId("at"), listOf(au)),
        )
        val result = runClipsForAssetQuery(project(listOf(a), tracks), input("a"), 100, 0)
        val rows = decodeRows(result.data)
        // Pin: both clips surface; track id round-trips to row.
        assertEquals(2, rows.size)
        val byClip = rows.associateBy { it.clipId }
        assertEquals("vt", byClip.getValue("v-clip").trackId)
        assertEquals("at", byClip.getValue("a-clip").trackId)
    }

    @Test fun clipsBoundToOtherAssetsAreNotMatched() {
        // Pin: only the queried assetId matches; other assets'
        // clips are filtered out.
        val a = assetWithId("a")
        val b = assetWithId("b")
        val matchA = videoClip("c-match", "a", Duration.ZERO, 5.seconds)
        val matchB = videoClip("c-other", "b", 5.seconds, 5.seconds)
        val track = Track.Video(TrackId("vt"), listOf(matchA, matchB))
        val rows = decodeRows(
            runClipsForAssetQuery(project(listOf(a, b), listOf(track)), input("a"), 100, 0).data,
        )
        assertEquals(listOf("c-match"), rows.map { it.clipId })
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsResultButTotalReflectsAllMatches() {
        val a = assetWithId("a")
        val clips = (1..5).map { videoClip("c$it", "a", (it * 5).seconds, 5.seconds) }
        val track = Track.Video(TrackId("vt"), clips)
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 2, 0)
        assertEquals(2, decodeRows(result.data).size, "page size = limit")
        assertEquals(5, result.data.total, "total reflects unfiltered count")
        assertEquals(2, result.data.returned)
    }

    @Test fun offsetSkipsFirstNRows() {
        val a = assetWithId("a")
        val clips = (1..5).map { videoClip("c$it", "a", (it * 5).seconds, 5.seconds) }
        val track = Track.Video(TrackId("vt"), clips)
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 2)
        val rows = decodeRows(result.data)
        // Sort by start: c1 (5s), c2 (10s), c3 (15s), c4 (20s), c5 (25s).
        // offset=2 → start at c3.
        assertEquals(3, rows.size)
        assertEquals("c3", rows[0].clipId)
    }

    // ── outputForLlm summary text ─────────────────────────────────

    @Test fun outputForLlmListsHeadFiveClipsAndEllipsisIfMore() {
        // Pin format: "<count> clip(s) reference asset <id>:
        // <c1>; <c2>; …; <c5>; …" (5 clips shown, ellipsis when
        // >5).
        val a = assetWithId("a")
        val clips = (1..7).map { videoClip("c$it", "a", (it * 5).seconds, 5.seconds) }
        val track = Track.Video(TrackId("vt"), clips)
        val out = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0).outputForLlm
        assertTrue("7 clip(s) reference asset a" in out, "count + asset id; got: $out")
        // First 5 clip ids appear in the head.
        for (i in 1..5) assertTrue("c$i" in out, "c$i must appear; got: $out")
        // Ellipsis when >5.
        assertTrue(out.endsWith("; …"), "must end with ellipsis; got: $out")
    }

    @Test fun outputForLlmShowsClipKindAndStartInBracketsForEachHead() {
        // Pin format: "<clipId> (<kind>/<trackId> @ <start>s)" for
        // each head row. UI consumers parse this for compact list
        // rendering.
        val a = assetWithId("a")
        val v = videoClip("c1", "a", 7.seconds, 5.seconds)
        val track = Track.Video(TrackId("vt"), listOf(v))
        val out = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0).outputForLlm
        assertTrue("c1 (video/vt @ 7.0s)" in out, "head format; got: $out")
    }

    @Test fun outputForLlmNoEllipsisWhenAtMostFiveResults() {
        val a = assetWithId("a")
        val clips = (1..3).map { videoClip("c$it", "a", (it * 5).seconds, 5.seconds) }
        val track = Track.Video(TrackId("vt"), clips)
        val out = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0).outputForLlm
        // No trailing ellipsis when count <= 5.
        assertTrue("…" !in out, "no ellipsis when count <= 5; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val a = assetWithId("a")
        val v = videoClip("c1", "a", Duration.ZERO, 5.seconds)
        val track = Track.Video(TrackId("vt"), listOf(v))
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_CLIPS_FOR_ASSET, result.data.select)
    }

    @Test fun titleFormatIncludesReturnedSlashTotal() {
        val a = assetWithId("a")
        val clips = (1..5).map { videoClip("c$it", "a", (it * 5).seconds, 5.seconds) }
        val track = Track.Video(TrackId("vt"), clips)
        val result = runClipsForAssetQuery(project(listOf(a), listOf(track)), input("a"), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
